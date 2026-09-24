package ru.whitebeef.beefsavebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.whitebeef.beefsavebot.model.OutputFormat;
import ru.whitebeef.beefsavebot.model.Quality;
import ru.whitebeef.beefsavebot.model.RequestType;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "download_requests")
@EntityListeners(AuditingEntityListener.class)

public class RequestLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ToString.Exclude
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(referencedColumnName = "id", name = "user_id")
  private UserInfo userInfo;

  @Column(name = "url")
  private String url;

  @CreatedDate
  @Column(name = "requested_at")
  private LocalDateTime requestedAt;

  @Column(name = "downloaded")
  private Boolean downloaded;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "request_type", nullable = false)
  private RequestType requestType = RequestType.DOWNLOAD;

  @Enumerated(EnumType.STRING)
  @Column(name = "quality")
  private Quality quality;

  @Enumerated(EnumType.STRING)
  @Column(name = "output_format")
  private OutputFormat outputFormat;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "file_size")
  private Long fileSize;
}
