package ru.whitebeef.beefsavebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.whitebeef.beefsavebot.service.cache.CachedMedia;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "media_cache")
public class MediaCacheEntry {

  @Id
  @Column(name = "cache_key")
  private String cacheKey;

  @Column(name = "file_id", nullable = false)
  private String fileId;

  @Enumerated(EnumType.STRING)
  @Column(name = "media_kind", nullable = false)
  private CachedMedia.Kind mediaKind;

  @Column(name = "file_size")
  private Long fileSize;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}
