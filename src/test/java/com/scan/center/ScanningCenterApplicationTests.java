package com.scan.center;

import com.scan.center.dto.RepositoryCatalogSaveDTO;
import com.scan.center.dto.UserSaveDTO;
import com.scan.center.model.SystemUser;
import com.scan.center.service.RepositoryCatalogService;
import com.scan.center.service.UserService;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest
public class ScanningCenterApplicationTests {
  @Autowired private RepositoryCatalogService repositoryCatalogService;
  @Autowired private UserService userService;

  @Test
  public void contextLoads() {}

  @Test
  public void applicationAutomaticallyAssociatesEnabledRepositories() {
    RepositoryCatalogSaveDTO repository = new RepositoryCatalogSaveDTO();
    repository.setRepositoryName("test/application-repository");
    repository.setRepositoryUrl("https://git.example.com/test/application-repository.git");
    repository.setApplication("F-BASE");
    repository.setEnabled(true);
    Long repositoryId = repositoryCatalogService.create(repository);

    UserSaveDTO user = new UserSaveDTO();
    user.setUsername("application_user");
    user.setDisplayName("应用用户");
    user.setApplication("F-BASE");
    user.setRoleCode("USER");
    user.setEnabled(true);
    Long userId = userService.create(user);

    SystemUser saved = userService.get(userId);
    Assert.assertTrue(saved.getRepositoryNames().contains("test/application-repository"));
    repositoryCatalogService.status(repositoryId, false);
    Assert.assertFalse(userService.get(userId).getRepositoryNames().contains("test/application-repository"));
  }
}
