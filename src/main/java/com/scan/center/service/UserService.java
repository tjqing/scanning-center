package com.scan.center.service;

import com.scan.center.common.PageResult;
import com.scan.center.dto.UserSaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.UserMapper;
import com.scan.center.model.SystemUser;
import java.util.Arrays;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserMapper mapper;
  public UserService(UserMapper mapper){this.mapper=mapper;}
  public PageResult<SystemUser> page(String keyword,String roleCode,Boolean enabled,int page,int size){int p=Math.max(page,1),s=Math.min(Math.max(size,1),100);return new PageResult<SystemUser>(mapper.page(keyword,roleCode,enabled,(p-1)*s,s),p,s,mapper.count(keyword,roleCode,enabled));}
  public SystemUser get(Long id){SystemUser user=mapper.findById(id);if(user==null)throw new BusinessException(50001,"用户不存在");return user;}
  @Transactional(rollbackFor=Exception.class) public Long create(UserSaveDTO dto){validate(dto,null);SystemUser user=new SystemUser();BeanUtils.copyProperties(dto,user);user.setUsername(dto.getUsername().trim());user.setEnabled(dto.getEnabled()==null?Boolean.TRUE:dto.getEnabled());mapper.insert(user);return user.getId();}
  @Transactional(rollbackFor=Exception.class) public void update(Long id,UserSaveDTO dto){get(id);validate(dto,id);SystemUser user=new SystemUser();BeanUtils.copyProperties(dto,user);user.setId(id);user.setUsername(dto.getUsername().trim());user.setEnabled(dto.getEnabled()==null?Boolean.TRUE:dto.getEnabled());mapper.update(user);}
  @Transactional(rollbackFor=Exception.class) public void status(Long id,boolean enabled){get(id);mapper.updateStatus(id,enabled);}
  @Transactional(rollbackFor=Exception.class) public void delete(Long id){SystemUser user=get(id);if("admin".equalsIgnoreCase(user.getUsername()))throw new BusinessException(50004,"内置管理员不能删除");mapper.logicalDelete(id);}
  private void validate(UserSaveDTO dto,Long id){if(!dto.getUsername().matches("[A-Za-z][A-Za-z0-9_.-]{2,63}"))throw new BusinessException(50002,"用户名须以字母开头，长度3-64位，可包含数字、点、下划线和短横线");if(!Arrays.asList("ADMIN","OPERATOR","VIEWER").contains(dto.getRoleCode()))throw new BusinessException(50003,"用户角色不合法");if(mapper.countUsername(dto.getUsername().trim(),id)>0)throw new BusinessException(50005,"用户名已存在");if(dto.getPhone()!=null&&!dto.getPhone().trim().isEmpty()&&!dto.getPhone().matches("[0-9+() -]{6,32}"))throw new BusinessException(50006,"手机号格式不正确");}
}
