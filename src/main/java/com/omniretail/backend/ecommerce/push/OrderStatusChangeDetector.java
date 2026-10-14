package com.omniretail.backend.ecommerce.push;

import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class OrderStatusChangeDetector
        implements PostUpdateEventListener, SmartInitializingSingleton {

    private final EntityManagerFactory entityManagerFactory;
    private final ApplicationEventPublisher publisher;

    public OrderStatusChangeDetector(
            EntityManagerFactory entityManagerFactory,
            ApplicationEventPublisher publisher
    ) {
        this.entityManagerFactory = entityManagerFactory;
        this.publisher = publisher;
    }

    @Override
    public void afterSingletonsInstantiated() {
        SessionFactoryImplementor factory =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        EventListenerRegistry registry = factory.getServiceRegistry()
                .getService(EventListenerRegistry.class);

        registry.appendListeners(EventType.POST_UPDATE, this);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (!(event.getEntity() instanceof Order order)) {
            return;
        }

        Object[] previous = event.getOldState();
        Object[] current = event.getState();

        if (previous == null || current == null) {
            return;
        }

        String[] properties = event.getPersister().getPropertyNames();

        for (int i = 0; i < properties.length; i++) {
            if (!"status".equals(properties[i])) {
                continue;
            }

            if (!(current[i] instanceof OrderStatus newStatus)) {
                return;
            }

            if (previous[i] == newStatus) {
                return;
            }

            UUID customerId = order.getCustomerId();

            if (customerId == null) {
                return;
            }

            publisher.publishEvent(new OrderStatusChangedEvent(
                    order.getTenantId(),
                    customerId,
                    order.getId(),
                    newStatus
            ));

            return;
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }
}
