package ca.musakaleem.remit.api.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** An operator account in {@code remit_api.app_user} with its roles (FR-SEC-02). */
@Entity
@Table(name = "app_user")
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "username", nullable = false, length = 50, unique = true, updatable = false)
  private String username;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private Set<Role> roles = EnumSet.noneOf(Role.class);

  /** For JPA only. */
  protected AppUser() {}

  /**
   * Creates an enabled account.
   *
   * @param username unique login name
   * @param passwordHash BCrypt hash of the password
   * @param roles at least one role
   * @param createdAt creation time
   */
  public AppUser(String username, String passwordHash, Set<Role> roles, Instant createdAt) {
    this.username = Objects.requireNonNull(username, "username");
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("A user needs at least one role");
    }
    this.roles = EnumSet.copyOf(roles);
    this.enabled = true;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Set<Role> getRoles() {
    return Set.copyOf(roles);
  }

  /** Blocks further logins; existing tokens stay valid until they expire. */
  public void disable() {
    this.enabled = false;
  }
}
