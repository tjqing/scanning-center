package com.scan.center.service;

import com.scan.center.common.ApplicationOptions;
import com.scan.center.common.AuditOperator;
import com.scan.center.common.PageResult;
import com.scan.center.dto.UserSaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.UserMapper;
import com.scan.center.mapper.RepositoryCatalogMapper;
import com.scan.center.model.RepositoryCatalog;
import com.scan.center.model.SystemUser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserMapper mapper;
  private final RepositoryCatalogMapper repositoryMapper;
  public UserService(UserMapper mapper, RepositoryCatalogMapper repositoryMapper){this.mapper=mapper;this.repositoryMapper=repositoryMapper;}
  public PageResult<SystemUser> page(String keyword,String roleCode,Boolean enabled,int page,int size){int p=Math.max(page,1),s=Math.min(Math.max(size,1),100);List<SystemUser> users=mapper.page(keyword,roleCode,enabled,(p-1)*s,s);for(SystemUser user:users)enrich(user);return new PageResult<SystemUser>(users,p,s,mapper.count(keyword,roleCode,enabled));}
  public SystemUser get(Long id){SystemUser user=mapper.findById(id);if(user==null)throw new BusinessException(50001,"用户不存在");enrich(user);return user;}
  @Transactional(rollbackFor=Exception.class) public Long create(UserSaveDTO dto){validate(dto,null);SystemUser user=new SystemUser();BeanUtils.copyProperties(dto,user);user.setUsername(dto.getUsername().trim());user.setApplication(dto.getApplication().trim());user.setEnabled(dto.getEnabled()==null?Boolean.TRUE:dto.getEnabled());mapper.insert(user);syncRepositories(user.getId(),user.getApplication());return user.getId();}
  @Transactional(rollbackFor=Exception.class) public void update(Long id,UserSaveDTO dto){get(id);validate(dto,id);SystemUser user=new SystemUser();BeanUtils.copyProperties(dto,user);user.setId(id);user.setUsername(dto.getUsername().trim());user.setApplication(dto.getApplication().trim());user.setEnabled(dto.getEnabled()==null?Boolean.TRUE:dto.getEnabled());mapper.update(user);syncRepositories(id,user.getApplication());}
  @Transactional(rollbackFor=Exception.class) public void status(Long id,boolean enabled){get(id);mapper.updateStatus(id,enabled);}
  @Transactional(rollbackFor=Exception.class) public void delete(Long id){SystemUser user=get(id);if("admin".equalsIgnoreCase(user.getUsername()))throw new BusinessException(50004,"内置管理员不能删除");mapper.deleteRepositoryRelations(id);mapper.logicalDelete(id);}
  private void validate(UserSaveDTO dto,Long id){if(!dto.getUsername().matches("[A-Za-z][A-Za-z0-9_.-]{2,63}"))throw new BusinessException(50002,"用户名须以字母开头，长度3-64位，可包含数字、点、下划线和短横线");if(!Arrays.asList("ADMIN","USER").contains(dto.getRoleCode()))throw new BusinessException(50003,"用户角色不合法");if(!ApplicationOptions.contains(dto.getApplication()))throw new BusinessException(50007,"请选择有效应用");if("ADMIN".equals(dto.getRoleCode())&&!"ALL".equals(dto.getApplication()))throw new BusinessException(50008,"管理员应用必须为ALL");if(mapper.countUsername(dto.getUsername().trim(),id)>0)throw new BusinessException(50005,"用户名已存在");}

  private void syncRepositories(Long userId,String application){mapper.deleteRepositoryRelations(userId);for(RepositoryCatalog repository:repositoryMapper.findEnabledByApplication(application))mapper.insertRepositoryRelation(userId,repository.getId(),AuditOperator.USER_ID,AuditOperator.USER_NAME);}
  private void enrich(SystemUser user){List<RepositoryCatalog> repositories=repositoryMapper.findEnabledByApplication(user.getApplication()==null?"ALL":user.getApplication());List<Long> ids=new ArrayList<Long>();List<String> names=new ArrayList<String>();for(RepositoryCatalog repository:repositories){ids.add(repository.getId());names.add(repository.getRepositoryName());}user.setRepositoryIds(ids);user.setRepositoryNames(names);}
}
