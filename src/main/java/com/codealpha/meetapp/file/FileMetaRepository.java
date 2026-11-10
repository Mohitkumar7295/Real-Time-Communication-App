package com.codealpha.meetapp.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetaRepository extends JpaRepository<FileMeta, Long> {

    List<FileMeta> findByRoomIdOrderByUploadedAtDesc(Long roomId);

    Optional<FileMeta> findByStoredName(String storedName);
}
