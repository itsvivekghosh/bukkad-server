package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "admins", indexes = {
        @Index(name = "idx_admin_phone", columnList = "phoneNumber")
})
@Getter
@Setter
public class Admin extends User {

    @Column(length = 100)
    private String email;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(name = "phone_number", length = 15)
    private String phoneNumber;
}