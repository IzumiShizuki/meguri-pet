package com.meguri.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Java Meguri core boundary.
 *
 * <p>The module is deliberately a thin, reactive shell.  Domain/runtime
 * contracts can be added below {@code com.meguri.core} without coupling the
 * existing Python and TypeScript implementations to the JVM build.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MeguriCoreApplication {

    private MeguriCoreApplication() {
        // Application entry points are not instantiated.
    }

    public static void main(String[] args) {
        SpringApplication.run(MeguriCoreApplication.class, args);
    }
}
