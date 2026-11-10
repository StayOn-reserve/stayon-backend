package com.stayon.stayon_backend.service;

import com.sun.net.httpserver.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local HTTP fixture, never proxies any request to KakaoPay. */
final class MockKakaoPayServer implements AutoCloseable {
    private final HttpServer server;private final JsonMapper json=JsonMapper.builder().build();
    final AtomicInteger readyCalls=new AtomicInteger(),approveCalls=new AtomicInteger(),orderCalls=new AtomicInteger(),cancelCalls=new AtomicInteger();
    final Map<String,JsonNode> preparations=new ConcurrentHashMap<>();final Set<String> approved=ConcurrentHashMap.newKeySet();
    final Set<String> canceled=ConcurrentHashMap.newKeySet();
    volatile JsonNode lastReady,lastApprove,lastCancel;volatile String lastAuthorization;
    volatile int readyStatus=200,approveStatus=200,orderHttpStatus=200,cancelStatus=200;
    volatile boolean partialCancel,badCancelTax,badCancelRemaining,badCancelCurrent,missingCancelAid,missingCanceledAt;
    volatile boolean invalidJson,badCid,badTid,badAmount,badUser,badUrl;
    volatile String lookupStatus;
    volatile String delayedOperation;
    volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
    MockKakaoPayServer(){
        try{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newCachedThreadPool(r->{var thread=new Thread(r,"mock-kakaopay");thread.setDaemon(true);return thread;}));
            server.createContext("/online/v1/payment/",this::handle);server.start();Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));
        }catch(Exception e){throw new IllegalStateException("Cannot start local mock HTTP server",e);}
    }
    String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    @Override public void close(){server.stop(0);}
    void reset(){
        readyCalls.set(0);approveCalls.set(0);orderCalls.set(0);cancelCalls.set(0);preparations.clear();approved.clear();canceled.clear();lastReady=null;lastApprove=null;lastCancel=null;lastAuthorization=null;
        cancelStatus=200;partialCancel=false;badCancelTax=false;badCancelRemaining=false;badCancelCurrent=false;missingCancelAid=false;missingCanceledAt=false;
        readyStatus=200;approveStatus=200;orderHttpStatus=200;invalidJson=false;badCid=false;badTid=false;badAmount=false;badUser=false;badUrl=false;lookupStatus=null;delayedOperation=null;
        entered=new CountDownLatch(0);release=new CountDownLatch(0);
    }
    void block(){entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    private void handle(HttpExchange exchange){
        try{
            String op=exchange.getRequestURI().getPath().substring("/online/v1/payment/".length());
            var request=json.readTree(exchange.getRequestBody().readAllBytes());lastAuthorization=exchange.getRequestHeaders().getFirst("Authorization");
            int status;Map<String,Object> result;
            if(op.equals("ready")){
                int n=readyCalls.incrementAndGet();lastReady=request;String tid="T"+String.format("%019d",n);preparations.put(tid,request);status=readyStatus;
                result=Map.of("tid",tid,"next_redirect_pc_url",badUrl?"https://evil.example.invalid/steal":"https://online-pay.kakao.com/mock/pc/"+n,
                        "next_redirect_mobile_url","https://online-pay.kakao.com/mock/mobile/"+n,"created_at","2026-11-09T10:00:00");
            }else{
                String tid=request.path("tid").asString();var ready=preparations.get(tid);
                if(ready==null){write(exchange,404,Map.of("error_code",-404));return;}
                if(op.equals("approve")){
                    approveCalls.incrementAndGet();lastApprove=request;status=approveStatus;
                    if(!"ephemeral-test-token".equals(request.path("pg_token").asString())||!ready.path("cid").asString().equals(request.path("cid").asString())
                            ||!ready.path("partner_order_id").asString().equals(request.path("partner_order_id").asString())
                            ||!ready.path("partner_user_id").asString().equals(request.path("partner_user_id").asString())
                            ||ready.path("total_amount").longValue()!=request.path("total_amount").longValue())status=400;
                    else approved.add(tid);
                }else if(op.equals("cancel")){
                    cancelCalls.incrementAndGet();lastCancel=request;status=cancelStatus;
                    if(!approved.contains(tid)||canceled.contains(tid)||!ready.path("cid").asString().equals(request.path("cid").asString())
                            ||ready.path("total_amount").longValue()!=request.path("cancel_amount").longValue()
                            ||ready.path("tax_free_amount").longValue()!=request.path("cancel_tax_free_amount").longValue())status=400;
                    else if(status==200&&!partialCancel)canceled.add(tid);
                }else if(op.equals("order")){orderCalls.incrementAndGet();status=orderHttpStatus;}
                else{write(exchange,404,Map.of("error_code",-404));return;}
                result=new LinkedHashMap<>();result.put("tid",badTid?"T9999999999999999999":tid);result.put("cid",badCid?"WRONGCID00":ready.path("cid").asString());
                result.put("partner_order_id",ready.path("partner_order_id").asString());result.put("partner_user_id",badUser?"wrong-user":ready.path("partner_user_id").asString());
                result.put("amount",Map.of("total",badAmount?1:ready.path("total_amount").longValue(),"tax_free",ready.path("tax_free_amount").longValue()));
                result.put("approved_at","2026-11-09T10:01:00");result.put("aid","A0000000000000000001");
                String state=lookupStatus!=null?lookupStatus:op.equals("cancel")&&partialCancel?"PART_CANCEL_PAYMENT":canceled.contains(tid)?"CANCEL_PAYMENT":approved.contains(tid)?"SUCCESS_PAYMENT":"READY";
                long total=ready.path("total_amount").longValue(),tax=ready.path("tax_free_amount").longValue();boolean full=state.equals("CANCEL_PAYMENT");
                result.put("status",state);result.put("canceled_amount",Map.of("total",full?total:0,"tax_free",badCancelTax?tax+1:full?tax:0));
                result.put("cancel_available_amount",Map.of("total",badCancelRemaining?1:0,"tax_free",0));
                result.put("approved_cancel_amount",Map.of("total",badCancelCurrent?1:total,"tax_free",tax));
                if(!missingCanceledAt)result.put("canceled_at","2026-11-09T10:02:00");if(missingCancelAid)result.remove("aid");
            }
            entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Mock gate timed out");
            if(op.equals(delayedOperation))Thread.sleep(4000);
            if(invalidJson){byte[] bytes="not-json".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
            else write(exchange,status,status==200?result:Map.of("error_code",-400,"error_message","fixture failure"));
        }catch(Exception ignored){/* Client may close the socket after its enforced timeout. */}finally{exchange.close();}
    }
    private void write(HttpExchange exchange,int status,Object value)throws Exception{
        byte[] bytes=json.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
}
