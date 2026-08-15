package com.signalforge.iam.repository;

import com.signalforge.iam.domain.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * The only lookup that is legitimately not tenant-scoped: at login time we do not yet know the
   * tenant, and email is globally unique precisely so this works. Everything downstream of
   * authentication is scoped.
   *
   * <p>Routed through a SECURITY DEFINER function because row-level security would otherwise hide
   * the row - there is no tenant bound yet, which is the whole point. The bypass is exactly one
   * query shape wide and is declared in the schema rather than hidden in a filter.
   */
  @Query(value = "SELECT * FROM sf_find_user_by_email(:email)", nativeQuery = true)
  Optional<User> findByEmail(@Param("email") String email);

  @Query(value = "SELECT sf_email_exists(:email)", nativeQuery = true)
  boolean existsByEmail(@Param("email") String email);

  /**
   * Tenant-scoped by id. Note the organization id is part of the query, not a check performed after
   * loading: a wrong tenant gets an empty Optional, indistinguishable from "does not exist", so
   * this cannot be used to probe whether another organization owns a given id.
   */
  @Query("SELECT u FROM User u WHERE u.id = :id AND u.organizationId = :organizationId")
  Optional<User> findByIdInOrganization(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);

  @Query("SELECT u FROM User u WHERE u.organizationId = :organizationId ORDER BY u.email")
  List<User> findAllInOrganization(@Param("organizationId") UUID organizationId);

  @Query(
      "SELECT COUNT(u) FROM User u WHERE u.organizationId = :organizationId AND u.roleCode = 'ADMIN' AND u.enabled = true")
  long countActiveAdmins(@Param("organizationId") UUID organizationId);
}
