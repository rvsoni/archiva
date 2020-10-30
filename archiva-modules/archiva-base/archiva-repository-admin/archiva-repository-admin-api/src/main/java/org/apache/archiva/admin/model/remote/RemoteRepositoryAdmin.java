package org.apache.archiva.admin.model.remote;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


import org.apache.archiva.admin.model.AuditInformation;
import org.apache.archiva.admin.model.RepositoryAdminException;
import org.apache.archiva.admin.model.beans.RemoteRepository;
import org.apache.archiva.indexer.ArchivaIndexingContext;

import java.util.List;
import java.util.Map;

/**
 * @author Olivier Lamy
 * @since 1.4-M1
 */
public interface RemoteRepositoryAdmin
{
    List<RemoteRepository> getRemoteRepositories()
        throws RepositoryAdminException;

    RemoteRepository getRemoteRepository( String repositoryId )
        throws RepositoryAdminException;

    Boolean deleteRemoteRepository( String repositoryId, AuditInformation auditInformation )
        throws RepositoryAdminException;

    Boolean addRemoteRepository( RemoteRepository remoteRepository, AuditInformation auditInformation )
        throws RepositoryAdminException;

    Boolean updateRemoteRepository( RemoteRepository remoteRepository, AuditInformation auditInformation )
        throws RepositoryAdminException;

    Map<String, RemoteRepository> getRemoteRepositoriesAsMap()
        throws RepositoryAdminException;

    /**
     * @param repository
     * @return
     * @throws RepositoryAdminException
     * @since 1.4-M2
     */
    ArchivaIndexingContext createIndexContext( RemoteRepository repository )
        throws RepositoryAdminException;
}
