package com.acme.reliable.core;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/**
 * Interceptor that hooks the TransactionSynchronizationRegistry into Micronaut's transaction lifecycle.
 */
@Singleton
@InterceptorBean(Transactional.class)
public class TransactionLifecycleInterceptor implements MethodInterceptor<Object, Object> {

    private final MicronautTransactionSynchronizationRegistry registry;

    public TransactionLifecycleInterceptor(MicronautTransactionSynchronizationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        registry.beginTransaction();
        try {
            Object result = context.proceed();
            registry.commitTransaction();
            return result;
        } catch (Exception e) {
            registry.rollbackTransaction();
            throw e;
        }
    }
}
