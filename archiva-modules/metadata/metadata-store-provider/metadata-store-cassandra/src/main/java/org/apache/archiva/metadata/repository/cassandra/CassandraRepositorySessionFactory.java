package org.apache.archiva.metadata.repository.cassandra;

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

import org.apache.archiva.configuration.ArchivaConfiguration;
import org.apache.archiva.metadata.repository.AbstractRepositorySessionFactory;
import org.apache.archiva.metadata.repository.MetadataRepositoryException;
import org.apache.archiva.metadata.repository.MetadataResolver;
import org.apache.archiva.metadata.repository.MetadataService;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.apache.archiva.metadata.repository.RepositorySessionFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * @author Olivier Lamy
 * @since 2.0.0
 */
@Service("repositorySessionFactory#cassandra")
public class CassandraRepositorySessionFactory extends AbstractRepositorySessionFactory
    implements RepositorySessionFactory
{

    @Inject
    @Named(value = "archivaConfiguration#default")
    private ArchivaConfiguration configuration;

    @Inject
    private MetadataResolver metadataResolver;

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private CassandraArchivaManager cassandraArchivaManager;

    @Inject
    private MetadataService metadataService;

    public void initialize()
    {
    }

    @Override
    protected void shutdown() {
        cassandraArchivaManager.shutdown();
    }


    @Override
    public RepositorySession createSession() throws MetadataRepositoryException
    {
        CassandraMetadataRepository metadataRepository =
            new CassandraMetadataRepository( metadataService, cassandraArchivaManager );
        return new RepositorySession( metadataRepository, metadataResolver );
    }

}
