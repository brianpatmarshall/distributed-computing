package com.boeing.constcatalog.model;

/**
 * Holds microservice classification for a Java class.
 * Determined by scanning class and method annotations in the AST.
 */
public record MicroServiceInfo(
    boolean isMicroService,
    String microServiceType
) {
    public static MicroServiceInfo none() {
        return new MicroServiceInfo(false, null);
    }

    public static MicroServiceInfo of(String type) {
        return new MicroServiceInfo(true, type);
    }
}
