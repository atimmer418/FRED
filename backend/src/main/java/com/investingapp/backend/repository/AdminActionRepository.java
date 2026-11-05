package com.investingapp.backend.repository;

import com.investingapp.backend.model.AdminAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** admin_actions: the audit trail of the /api/admin/** surface (spec 12.2). Rows are never deleted. */
@Repository
public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    /** The most recent actions on one user, newest first, for the admin status dump. */
    List<AdminAction> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}
