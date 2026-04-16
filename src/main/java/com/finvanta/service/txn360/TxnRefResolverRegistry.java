package com.finvanta.service.txn360;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * CBS Transaction-360 resolver registry per Finacle TRAN_INQUIRY dispatcher.
 *
 * <p>Holds every {@link TxnRefResolver} bean in {@link org.springframework.core.annotation.Order}
 * order and routes an incoming reference to the first strategy that claims it.
 * New reference families are added by publishing a new {@link TxnRefResolver}
 * {@code @Component}; no change to the controller or this registry is needed.
 */
@Component
public class TxnRefResolverRegistry {

    private final List<TxnRefResolver> resolvers;

    public TxnRefResolverRegistry(List<TxnRefResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    public TxnRefResolver.TxnRefResolution resolve(String tenantId, String reference) {
        for (TxnRefResolver resolver : resolvers) {
            if (resolver.supports(reference)) {
                return resolver.resolve(tenantId, reference);
            }
        }
        // Unreachable in practice -- FallbackRefResolver.supports() is unconditional.
        return TxnRefResolver.TxnRefResolution.empty();
    }
}
