package ru.whitebeef.beefsavebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "user_infos")
public class UserInfo {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "telegram_id", unique = true, nullable = false)
  private Long telegramUserId;

  @Column(name = "username")
  private String username;

  @Column(name = "first_name")
  private String firstName;

  @Column(name = "last_name")
  private String lastName;
}