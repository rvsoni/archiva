package org.apache.archiva.security;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import junit.framework.TestCase;
import net.sf.ehcache.CacheManager;
import org.apache.archiva.configuration.ArchivaConfiguration;
import org.apache.archiva.configuration.ManagedRepositoryConfiguration;
import org.apache.archiva.redback.rbac.RBACManager;
import org.apache.archiva.redback.rbac.RbacObjectNotFoundException;
import org.apache.archiva.redback.rbac.UserAssignment;
import org.apache.archiva.redback.role.RoleManager;
import org.apache.archiva.redback.system.SecuritySystem;
import org.apache.archiva.redback.users.User;
import org.apache.archiva.redback.users.UserManager;
import org.apache.archiva.redback.users.UserManagerException;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.security.common.ArchivaRoleConstants;
import org.apache.archiva.test.utils.ArchivaSpringJUnit4ClassRunner;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ContextConfiguration;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * AbstractSecurityTest
 */
@RunWith(ArchivaSpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath*:/META-INF/spring-context.xml", "classpath*:/spring-context.xml" })
public abstract class AbstractSecurityTest
    extends TestCase
{

    protected Logger log = LoggerFactory.getLogger( getClass() );

    protected static final String USER_GUEST = "guest";

    protected static final String USER_ADMIN = "admin";

    protected static final String USER_ALPACA = "alpaca";

    @Inject
    @Named(value = "securitySystem#testable")
    protected SecuritySystem securitySystem;

    @Inject
    @Named(value = "rbacManager#memory")
    protected RBACManager rbacManager;

    @Inject
    protected RoleManager roleManager;

    @Inject
    @Named(value = "archivaConfiguration#default")
    private ArchivaConfiguration archivaConfiguration;

    @Inject
    private RepositoryRegistry repositoryRegistry;

    @Inject
    protected UserRepositories userRepos;

    protected void setupRepository( String repoId )
        throws Exception
    {
        // Add repo to configuration.
        ManagedRepositoryConfiguration repoConfig = new ManagedRepositoryConfiguration();
        repoConfig.setId( repoId );
        repoConfig.setName( "Testable repo <" + repoId + ">" );
        repoConfig.setLocation( Paths.get( "target/test-repo/" + repoId ).toString() );
        if ( !archivaConfiguration.getConfiguration().getManagedRepositoriesAsMap().containsKey( repoId ) )
        {
            repositoryRegistry.putRepository( repoConfig );
        }

        // Add repo roles to security.
        userRepos.createMissingRepositoryRoles( repoId );
    }

    protected void assignRepositoryObserverRole( String principal, String repoId )
        throws Exception
    {
        roleManager.assignTemplatedRole( ArchivaRoleConstants.TEMPLATE_REPOSITORY_OBSERVER, repoId, principal );
    }

    protected User createUser( String principal, String fullname )
        throws UserManagerException
    {
        UserManager userManager = securitySystem.getUserManager();

        User user = userManager.createUser( principal, fullname, principal + "@testable.archiva.apache.org" );
        securitySystem.getPolicy().setEnabled( false );
        userManager.addUser( user );
        securitySystem.getPolicy().setEnabled( true );

        return user;
    }

    @Override
    @Before
    public void setUp()
        throws Exception
    {
        super.setUp();

        Path srcConfig = Paths.get( "src/test/resources/repository-archiva.xml" );
        Path destConfig = Paths.get( "target/test-conf/archiva.xml" );

        Files.createDirectories(destConfig.getParent());
        Files.deleteIfExists(destConfig);

        FileUtils.copyFile( srcConfig.toFile(), destConfig.toFile() );

        // Some basic asserts.
        assertNotNull( securitySystem );
        assertNotNull( rbacManager );
        assertNotNull( roleManager );
        assertNotNull( userRepos );
        assertNotNull( archivaConfiguration );

        // Setup Admin User.
        User adminUser = createUser( USER_ADMIN, "Admin User" );
        roleManager.assignRole( ArchivaRoleConstants.TEMPLATE_SYSTEM_ADMIN, adminUser.getUsername() );

        // Setup Guest User.
        User guestUser = createUser( USER_GUEST, "Guest User" );
        roleManager.assignRole( ArchivaRoleConstants.TEMPLATE_GUEST, guestUser.getUsername() );

        repositoryRegistry.setArchivaConfiguration(archivaConfiguration);
        repositoryRegistry.reload();
    }

    protected void restoreGuestInitialValues( String userId )
        throws Exception
    {
        UserAssignment userAssignment = null;
        try
        {
            userAssignment = rbacManager.getUserAssignment( userId );
        }
        catch ( RbacObjectNotFoundException e )
        {
            log.info( "ignore RbacObjectNotFoundException for id {} during restoreGuestInitialValues", userId );
            return;
        }
        userAssignment.setRoleNames( new ArrayList( Arrays.asList( "Guest" ) ) );
        rbacManager.saveUserAssignment( userAssignment );
        CacheManager.getInstance().clearAll();
    }
}
