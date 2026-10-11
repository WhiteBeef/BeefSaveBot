package ru.whitebeef.beefsavebot.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ключ API в заголовке {@code X-Api-Key}. Ключ не задан ({@code API_KEY} пустой) — API выключен
 * целиком: открыть его без ключа нельзя даже в локальной сети.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Api-Key";

  private final byte[] key;

  public ApiKeyFilter(@Value("${api.key:}") String key) {
    this.key = key == null || key.isBlank() ? null : key.strip().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (key == null) {
      deny(response, 503, "API выключен: задайте API_KEY");
      return;
    }
    String given = request.getHeader(HEADER);
    // сравнение за постоянное время — по нему не подобрать ключ
    if (given == null || !MessageDigest.isEqual(key, given.strip().getBytes(StandardCharsets.UTF_8))) {
      deny(response, 401, "Нужен заголовок " + HEADER + " с ключом API");
      return;
    }
    chain.doFilter(request, response);
  }

  private static void deny(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
  }
}
