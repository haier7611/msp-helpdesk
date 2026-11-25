package com.ticketsystem.api.auth;

import com.ticketsystem.api.auth.AuthTokenRepository.TokenRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and resolves single-use link tokens (C7). Callers run inside their own transaction, so the
 * token, the action it allows and the email carrying it commit or roll back together.
 */
@Service
public class TokenService {

  /** A freshly issued token. {@code token} is the raw value for the link; it is never stored. */
  public record Issued(long id, String token, Instant expiresAt) {
    @Override
    public String toString() {
      return "Issued[id=" + id + ", expiresAt=" + expiresAt + "]";
    }
  }

  private final AuthTokenRepository tokens;
  private final Clock clock;

  public TokenService(AuthTokenRepository tokens, Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  /** Issues a token, voiding the user's older unused tokens of the same purpose. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Issued issue(long userId, TokenPurpose purpose, Duration validFor) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(validFor);
    String token = SecureTokens.newToken();
    String tokenHash = SecureTokens.sha256Hex(token);
    tokens.deleteUnused(userId, purpose);
    long id = tokens.insert(userId, purpose, tokenHash, null, now, expiresAt);
    return new Issued(id, token, expiresAt);
  }

  /**
   * The usable (unused, unrevoked, unexpired) token row for a raw token, locked for the caller's
   * transaction; empty for anything else. Does not consume it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<TokenRow> findUsableForUpdate(String rawToken, TokenPurpose purpose) {
    if (!SecureTokens.looksLikeToken(rawToken)) {
      return Optional.empty();
    }
    String hash = SecureTokens.sha256Hex(rawToken);
    return tokens
        .findForUpdate(hash, purpose)
        .filter(row -> SecureTokens.constantTimeEquals(row.tokenHash(), hash))
        .filter(row -> row.usableAt(clock.instant()));
  }

  /** Read-only check used by the accept-invitation page; never consumes. */
  public Optional<TokenRow> findUsable(String rawToken, TokenPurpose purpose) {
    if (!SecureTokens.looksLikeToken(rawToken)) {
      return Optional.empty();
    }
    String hash = SecureTokens.sha256Hex(rawToken);
    return tokens
        .find(hash, purpose)
        .filter(row -> SecureTokens.constantTimeEquals(row.tokenHash(), hash))
        .filter(row -> row.usableAt(clock.instant()));
  }

  /** Marks a locked, usable token as used. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void consume(TokenRow row) {
    if (!tokens.markUsed(row.id(), clock.instant())) {
      throw new IllegalStateException("token already used");
    }
  }
}
