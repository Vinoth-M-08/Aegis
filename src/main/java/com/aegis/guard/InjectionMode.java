package com.aegis.guard;

public enum InjectionMode {
    /** Reject the request. */
    BLOCK,
    /** Forward the request but record it in the audit log. */
    FLAG,
    /** Skip detection. */
    OFF
}
