package com.bhukkad.common;

import com.bhukkad.common.security.BlockHoundSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public abstract class BlockHoundTestBase {

    protected String packagePrefix() {
        return "com.bhukkad";
    }

    @BeforeAll
    static void installBlockHound() {
        if (!BlockHoundSupport.isSupported()) {
            return;
        }
        BlockHoundSupport.installOnce();
    }

    @Test
    void harnessGuard_blockHoundDetectsBlockingCallOnNonBlockingThread() {
        Assumptions.assumeTrue(BlockHoundSupport.isSupported(),
                "BlockHound not supported on JDK " + Runtime.version().feature());
        reactor.test.StepVerifier.create(
                        Mono.fromCallable(() -> {
                                    Thread.sleep(20);
                                    return 1;
                                })
                                .subscribeOn(Schedulers.parallel()))
                .expectError(BlockingOperationError.class)
                .verify();
    }

    @Test
    void scannedReactiveMethods_neverBlockOnEventLoopThreads() {
        Assumptions.assumeTrue(BlockHoundSupport.isSupported(),
                "BlockHound not supported on JDK " + Runtime.version().feature());

        List<Class<?>> classes = scanClasses(packagePrefix());
        List<String> violations = new ArrayList<>();

        int tested = 0;
        for (Class<?> clazz : classes) {
            if (clazz.isInterface()
                    || clazz.isEnum()
                    || clazz.isAnnotation()
                    || clazz.isLocalClass()
                    || clazz.isSynthetic()
                    || clazz.getName().contains("$")) {
                continue;
            }
            if (Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }

            List<Method> reactiveMethods = findReactiveMethods(clazz);
            for (Method method : reactiveMethods) {
                if (method.getParameterCount() > 4) {
                    continue;
                }
                Object instance;
                try {
                    instance = Mockito.mock(clazz, Mockito.RETURNS_DEEP_STUBS);
                } catch (Exception e) {
                    continue;
                }
                Object[] args = new Object[method.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = Mockito.mock(method.getParameterTypes()[i]);
                }
                final Object target = instance;
                final Method m = method;
                final Object[] a = args;
                try {
                    assertThatCode(() -> {
                                java.time.Duration ignored = reactor.test.StepVerifier.create(
                                                Mono.fromCallable(() -> {
                                                    try {
                                                        return m.invoke(target, a);
                                                    } catch (Exception ex) {
                                                        throw new RuntimeException(ex);
                                                    }
                                                })
                                                .subscribeOn(Schedulers.parallel()))
                                        .expectError(BlockingOperationError.class)
                                        .verify();
                            })
                            .as("BlockHound violation in %s.%s on non-blocking thread", clazz.getName(), method.getName())
                            .doesNotThrowAnyException();
                    tested++;
                } catch (BlockingOperationError ex) {
                    violations.add(clazz.getName() + "#" + method.getName() + " -> " + ex.getMessage());
                } catch (AssertionError ex) {
                    if (ex.getCause() instanceof BlockingOperationError boe) {
                        violations.add(clazz.getName() + "#" + method.getName() + " -> " + boe.getMessage());
                    }
                }
            }
        }

        assertThat(violations)
                .as("BlockHound violations found in %d tested reactive methods across %d classes", tested, classes.size())
                .isEmpty();
    }

    private static List<Class<?>> scanClasses(String packagePrefix) {
        List<Class<?>> classes = new ArrayList<>();
        String path = packagePrefix.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader effectiveLoader = classLoader != null ? classLoader : BlockHoundTestBase.class.getClassLoader();
        try {
            java.util.Enumeration<URL> resources = effectiveLoader.getResources(path);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (url == null) {
                    continue;
                }
                Path root;
                try {
                    root = Paths.get(url.toURI());
                } catch (URISyntaxException e) {
                    continue;
                }
                if (Files.notExists(root) || !Files.isDirectory(root)) {
                    continue;
                }
                Files.walk(root).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String className = pathToClass(root, p);
                    try {
                        Class<?> clazz = Class.forName(className, false, effectiveLoader);
                        classes.add(clazz);
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan package " + packagePrefix, e);
        }
        return classes;
    }

    private static String pathToClass(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative.replace('/', '.').replaceAll("\\.class$", "");
    }

    private static List<Method> findReactiveMethods(Class<?> clazz) {
        List<Method> reactive = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (Mono.class.equals(returnType) || Flux.class.equals(returnType)) {
                reactive.add(method);
            }
        }
        return reactive;
    }
}
