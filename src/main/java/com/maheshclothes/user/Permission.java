package com.maheshclothes.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "permissions")
@Getter
@Setter
public class Permission {

    @Id
    @Column(length = 60)
    private String code;

    @Column(name = "group_name", nullable = false, length = 40)
    private String groupName;

    @Column(nullable = false, length = 200)
    private String description;
}
