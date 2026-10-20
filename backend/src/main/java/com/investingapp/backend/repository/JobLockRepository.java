package com.investingapp.backend.repository;

import com.investingapp.backend.model.JobLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface JobLockRepository extends JpaRepository<JobLock, String> {

    /** 1 when the row was created, 0 when the name already exists (INSERT IGNORE on the primary key). */
    @Modifying
    @Query(value = "INSERT IGNORE INTO job_locks (name, locked_until, locked_by) VALUES (:name, :until, :owner)",
            nativeQuery = true)
    int insertIgnore(@Param("name") String name, @Param("until") LocalDateTime until, @Param("owner") String owner);

    /** 1 when an expired lease was taken over, 0 while the previous holder's lease still runs. */
    @Modifying
    @Query(value = "UPDATE job_locks SET locked_until = :until, locked_by = :owner "
            + "WHERE name = :name AND locked_until < :now", nativeQuery = true)
    int renew(@Param("name") String name, @Param("now") LocalDateTime now,
            @Param("until") LocalDateTime until, @Param("owner") String owner);

    /** Deletes the row only while this process still owns it. */
    @Modifying
    @Query(value = "DELETE FROM job_locks WHERE name = :name AND locked_by = :owner", nativeQuery = true)
    int deleteOwned(@Param("name") String name, @Param("owner") String owner);
}
