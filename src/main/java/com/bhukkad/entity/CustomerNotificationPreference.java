package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_notification_preferences")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class CustomerNotificationPreference {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "email_enabled", nullable = false)
    private Boolean emailEnabled = true;

    @Column(name = "sms_enabled", nullable = false)
    private Boolean smsEnabled = true;

    @Column(name = "push_enabled", nullable = false)
    private Boolean pushEnabled = true;

    @Column(name = "whatsapp_enabled", nullable = false)
    private Boolean whatsappEnabled = true;

    @Column(name = "order_updates_enabled", nullable = false)
    private Boolean orderUpdatesEnabled = true;

    @Column(name = "promotions_enabled", nullable = false)
    private Boolean promotionsEnabled = true;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static CustomerNotificationPreference defaults(Customer customer) {
        CustomerNotificationPreference pref = new CustomerNotificationPreference();
        pref.setCustomer(customer);
        return pref;
    }
}
