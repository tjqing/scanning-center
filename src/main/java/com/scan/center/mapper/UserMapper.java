package com.scan.center.mapper;

import com.scan.center.model.SystemUser;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {
  List<SystemUser> page(@Param("keyword") String keyword,@Param("roleCode") String roleCode,@Param("enabled") Boolean enabled,@Param("offset") int offset,@Param("size") int size);
  long count(@Param("keyword") String keyword,@Param("roleCode") String roleCode,@Param("enabled") Boolean enabled);
  SystemUser findById(Long id);
  /** 按用户名查启用用户（登录） */
  SystemUser findEnabledByUsername(@Param("username") String username);
  /** 登录页下拉（已废弃，保留兼容） */
  List<SystemUser> findEnabledForLogin();
  int countUsername(@Param("username") String username,@Param("excludeId") Long excludeId);
  int insert(SystemUser user);
  int update(SystemUser user);
  int updateStatus(@Param("id") Long id,@Param("enabled") Boolean enabled);
  int logicalDelete(Long id);
  int deleteRepositoryRelations(Long userId);
  int insertRepositoryRelation(@Param("userId") Long userId, @Param("repositoryId") Long repositoryId,
      @Param("createUserId") Long createUserId, @Param("createUserName") String createUserName);
  int countRepositoryRelation(@Param("userId") Long userId, @Param("repositoryId") Long repositoryId);
}
