package com.scan.center.mapper;

import com.scan.center.model.RepositoryCatalog;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RepositoryCatalogMapper {
  List<RepositoryCatalog> page(@Param("keyword") String keyword, @Param("application") String application,
      @Param("enabled") Boolean enabled, @Param("offset") int offset, @Param("size") int size);
  long count(@Param("keyword") String keyword, @Param("application") String application,
      @Param("enabled") Boolean enabled);
  RepositoryCatalog findById(Long id);
  /** 取一条已配置 SSH 私钥的代码库，供 applyGitCredential 复用密文 */
  RepositoryCatalog findSharedSshCredential();
  List<RepositoryCatalog> findEnabledByApplication(@Param("application") String application);
  int countName(@Param("name") String name, @Param("excludeId") Long excludeId);
  int insert(RepositoryCatalog value);
  int update(RepositoryCatalog value);
  int updateStatus(@Param("id") Long id, @Param("enabled") Boolean enabled);
  int logicalDelete(Long id);
  int countUserReference(Long id);
  int deleteUserRelations(Long id);
  int insertUserRelations(@Param("repositoryId") Long repositoryId, @Param("application") String application,
      @Param("createUserId") Long createUserId, @Param("createUserName") String createUserName);
}
