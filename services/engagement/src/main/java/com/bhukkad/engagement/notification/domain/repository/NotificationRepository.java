package com.bhukkad.engagement.notification.domain.repository;

import com.bhukkad.engagement.notification.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientAndChannel(String recipient, String channel);
}