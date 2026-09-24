package ru.whitebeef.beefsavebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.whitebeef.beefsavebot.model.MusicProvider;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;

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

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "last_seen_at")
  private LocalDateTime lastSeenAt;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "quality", nullable = false)
  private Quality quality = Quality.HIGH;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "output_format", nullable = false)
  private OutputFormat outputFormat = OutputFormat.MP4;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "music_provider", nullable = false)
  private MusicProvider musicProvider = MusicProvider.YANDEX;

  @Builder.Default
  @Column(name = "banned", nullable = false)
  private Boolean banned = false;

  public String displayName() {
    String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName))
        .trim();
    if (username != null && !username.isBlank()) {
      return name.isBlank() ? "@" + username : name + " (@" + username + ")";
    }
    return name.isBlank() ? String.valueOf(telegramUserId) : name;
  }
}
