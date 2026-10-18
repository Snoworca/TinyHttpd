package com.snoworca.tinyhttpd.junit;

/**
 * JUnit category for tests that describe the intended behavior but fail on the inherited NanoHttpd code.
 * They are excluded from {@code test} and run by {@code knownDefectTest}. Remove the category once the defect is
 * fixed so the test joins the regular suite.
 */
public interface KnownDefect {
}
