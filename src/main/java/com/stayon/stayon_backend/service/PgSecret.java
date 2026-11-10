package com.stayon.stayon_backend.service;

import java.util.Arrays;

public final class PgSecret implements AutoCloseable {
    private char[] value;
    public PgSecret(char[] value){if(value==null||value.length==0)throw new IllegalArgumentException("Missing credential");this.value=value.clone();}
    public synchronized char[] copy(){if(value==null)throw new IllegalStateException("Credential is closed");return value.clone();}
    @Override public synchronized void close(){if(value!=null){Arrays.fill(value,'\0');value=null;}}
    @Override public String toString(){return "PgSecret[REDACTED]";}
}
