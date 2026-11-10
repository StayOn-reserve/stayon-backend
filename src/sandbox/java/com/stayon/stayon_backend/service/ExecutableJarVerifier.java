package com.stayon.stayon_backend.service;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** Runs only fresh owned MySQL + the actual packaged application. Never invokes any PG operation. */
public final class ExecutableJarVerifier {
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Executable and verification support archives required");
        Path archive=Path.of(args[0]).toAbsolutePath(),support=Path.of(args[1]).toAbsolutePath();
        inspect(archive);
        Path root=Path.of("build","jar-verification",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(root);
        try(var mysql=OwnedMySql.start()){
            Path config=root.resolve("application.properties");
            Files.writeString(config,"""
                    spring.profiles.active=jar-verification
                    spring.datasource.url=${JAR_TEST_DB_URL}
                    spring.datasource.username=root
                    spring.datasource.password=
                    spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
                    spring.jpa.hibernate.ddl-auto=create-drop
                    spring.jpa.open-in-view=false
                    spring.sql.init.mode=never
                    spring.data.redis.host=127.0.0.1
                    spring.data.redis.port=1
                    spring.data.redis.repositories.enabled=false
                    server.address=127.0.0.1
                    server.port=${JAR_TEST_SERVER_PORT}
                    business-api.url=http://127.0.0.1:1
                    business-api.api-key=unused-jar-verification-fixture
                    jwt.secret=${JAR_TEST_ADMIN_KEY}
                    jwt.access-token-expiration=60000
                    jwt.refresh-token-expiration=60000
                    customer.jwt.secret=${JAR_TEST_CUSTOMER_KEY}
                    customer.jwt.issuer=jar-verification
                    customer.jwt.audience=jar-verification-browser
                    customer.otp.hmac-secret=${JAR_TEST_OTP_KEY}
                    payment.kakaopay.enabled=true
                    payment.kakaopay.mock-transport=false
                    payment.kakaopay.live-enabled=false
                    payment.kakaopay.api-origin=https://open-api.kakaopay.com
                    payment.merchant.environment=SANDBOX
                    payment.recovery.enabled=false
                    payment.cancellation-worker.enabled=false
                    payment.webhook.enabled=false
                    logging.level.org.springframework.boot=ERROR
                    verification.enabled=true
                    verification.marker=${JAR_VERIFICATION_MARKER}
                    verification.report=${JAR_VERIFICATION_REPORT}
                    """);
            // First exercise the manifest's real java -jar entry point and the unauthenticated customer boundary.
            Process executable=launch(archive,null,config,mysql.jdbcUrl(),root.resolve("java-jar.log"),root.resolve("unused.properties"));
            try{
                awaitStarted(executable,root.resolve("java-jar.log"));
                int port=Integer.parseInt(lastEnvironmentPort);
                var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/customer/reservation")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.discarding());
                if(response.statusCode()!=401)throw new IllegalStateException("Packaged customer authentication boundary failed");
            }finally{stop(executable);}
            // Then load only the diagnostic initializer externally; application classes/dependencies remain those in the JAR.
            Path evidence=root.resolve("beans.properties");
            Process inspected=launch(archive,support,config,mysql.jdbcUrl(),root.resolve("beans.log"),evidence);
            try{
                if(!inspected.waitFor(60,TimeUnit.SECONDS)||inspected.exitValue()!=0||!Files.exists(evidence))throw new IllegalStateException("Packaged bean verification failed; inspect isolated logs");
                if(Files.readString(evidence).lines().filter(s->s.endsWith("=true")).count()!=7)throw new IllegalStateException("Incomplete bean evidence");
            }finally{stop(inspected);}
            Files.writeString(config,Files.readString(config).replace("spring.profiles.active=jar-verification","spring.profiles.active=dev"));
            Path developmentEvidence=root.resolve("development-beans.properties");
            Process development=launch(archive,support,config,mysql.jdbcUrl(),root.resolve("development-beans.log"),developmentEvidence);
            try{
                if(!development.waitFor(60,TimeUnit.SECONDS)||development.exitValue()!=0||!Files.exists(developmentEvidence))throw new IllegalStateException("Development resolver JAR verification failed; inspect isolated logs");
            }finally{stop(development);}
            Files.writeString(root.resolve("result.properties"),"archiveIsolation=true\njavaJarStarted=true\ncustomerUnauthenticated401=true\nbeanVerification=true\nexternalPgCalls=0\n");
        }
        System.out.println("Executable JAR verified; isolated evidence: "+root);
    }
    private static String lastEnvironmentPort;
    private static Process launch(Path archive,Path support,Path config,String jdbc,Path log,Path report)throws Exception{
        int port;try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}lastEnvironmentPort=Integer.toString(port);
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();var command=new ArrayList<String>();command.add(java);
        if(support==null){command.add("-jar");command.add(archive.toString());}
        else{command.add("-Dloader.path="+support.toUri());command.add("-cp");command.add(archive.toString());command.add("org.springframework.boot.loader.launch.PropertiesLauncher");}
        command.add("--spring.config.location="+config.toUri());
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();var keep=new HashMap<String,String>();
        for(String key:List.of("SystemRoot","WINDIR","TEMP","TMP"))if(env.containsKey(key))keep.put(key,env.get(key));env.clear();env.putAll(keep);
        env.put("JAR_TEST_DB_URL",jdbc);env.put("JAR_TEST_SERVER_PORT",Integer.toString(port));
        for(String key:List.of("ADMIN","CUSTOMER","OTP"))env.put("JAR_TEST_"+key+"_KEY","fixture-"+UUID.randomUUID()+UUID.randomUUID());
        env.put("JAR_VERIFICATION_MARKER",UUID.randomUUID().toString());env.put("JAR_VERIFICATION_REPORT",report.toString().replace('\\','/'));
        env.put("KAKAOPAY_SANDBOX_OWNER_ID","1");env.put("KAKAOPAY_SANDBOX_MERCHANT_ID","jar-fixture");
        env.put("KAKAOPAY_SANDBOX_SECRET_KIND","DEV");env.put("KAKAOPAY_SANDBOX_DEV_SECRET","synthetic-jar-fixture");
        return builder.start();
    }
    private static void awaitStarted(Process child,Path log)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<deadline){
            if(Files.exists(log)&&Files.readString(log).contains("Started StayonBackendApplication"))return;
            if(!child.isAlive())throw new IllegalStateException("Executable JAR exited before startup; inspect isolated log");Thread.sleep(100);
        }throw new IllegalStateException("Executable startup timeout");
    }
    private static void stop(Process child)throws Exception{
        if(child.isAlive()){child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
    private static void inspect(Path archive)throws Exception{
        try(var jar=new JarFile(archive.toFile())){
            var manifest=jar.getManifest().getMainAttributes();
            if(!"org.springframework.boot.loader.launch.JarLauncher".equals(manifest.getValue("Main-Class"))
                    ||!"com.stayon.stayon_backend.StayonBackendApplication".equals(manifest.getValue("Start-Class")))throw new IllegalStateException("Executable manifest mismatch");
            for(var entry:Collections.list(jar.entries())){
                String name=entry.getName();
                if(name.contains("spring-boot-devtools")||name.contains("KakaoPaySandbox")||name.contains("FakePg")||name.contains("JarSmokeInitializer")
                        ||name.startsWith("BOOT-INF/lib/h2-")||name.startsWith("BOOT-INF/lib/junit"))throw new IllegalStateException("Test/development content in executable archive");
            }
            if(jar.getEntry("BOOT-INF/classes/com/stayon/stayon_backend/service/PaymentOrchestrator.class")==null)throw new IllegalStateException("Missing application classes");
        }
    }
}
