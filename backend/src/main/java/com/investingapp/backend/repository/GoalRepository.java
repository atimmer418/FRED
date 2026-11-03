package com.investingapp.backend.repository;

import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalRepository extends JpaRepository<Goal, Long> {

    List<Goal> findByUserOrderBySortOrderAscCreatedAtAsc(User user);

    Optional<Goal> findByIdAndUser(Long id, User user);

    long countByUserAndCompletedFalse(User user);

    boolean existsByUserAndClientId(User user, String clientId);

    void deleteAllByUser(User user);
}
