package org.apache.archiva.repository.maven.metadata;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import junit.framework.TestCase;
import org.apache.archiva.metadata.maven.MavenMetadataReader;
import org.apache.archiva.model.ArchivaRepositoryMetadata;
import org.apache.archiva.repository.metadata.RepositoryMetadataException;
import org.apache.archiva.test.utils.ArchivaBlockJUnit4ClassRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * RepositoryMetadataReaderTest
 *
 *
 */
@RunWith( ArchivaBlockJUnit4ClassRunner.class )
public class RepositoryMetadataReaderTest
    extends TestCase
{

    private Path getRepositoryPath(String repoName) {
        try
        {
            return Paths.get( Thread.currentThread( ).getContextClassLoader( ).getResource( "repositories/" + repoName ).toURI( ) );
        }
        catch ( URISyntaxException e )
        {
            throw new RuntimeException( "Could not resolve repository path " + e.getMessage( ), e );
        }
    }

    @Test
    public void testLoadSimple()
        throws RepositoryMetadataException
    {
        Path defaultRepoDir = getRepositoryPath( "default-repository" );
        Path metadataFile = defaultRepoDir.resolve( "org/apache/maven/shared/maven-downloader/maven-metadata.xml" );

        MavenMetadataReader metadataReader = new MavenMetadataReader( );

        ArchivaRepositoryMetadata metadata = metadataReader.read( metadataFile );

        assertNotNull( metadata );
        assertEquals( "Group Id", "org.apache.maven.shared", metadata.getGroupId() );
        assertEquals( "Artifact Id", "maven-downloader", metadata.getArtifactId() );
        assertEquals( "Released Version", "1.1", metadata.getReleasedVersion() );
        assertEquals( "List of Available Versions", 2, metadata.getAvailableVersions().size() );
        assertTrue( "Available version 1.0", metadata.getAvailableVersions().contains( "1.0" ) );
        assertTrue( "Available version 1.1", metadata.getAvailableVersions().contains( "1.1" ) );
    }

    @Test
    public void testLoadComplex()
        throws RepositoryMetadataException
    {
        Path defaultRepoDir = getRepositoryPath( "default-repository" );
        Path metadataFile = defaultRepoDir.resolve( "org/apache/maven/samplejar/maven-metadata.xml" );
        MavenMetadataReader metadataReader = new MavenMetadataReader( );

        ArchivaRepositoryMetadata metadata = metadataReader.read( metadataFile );

        assertNotNull( metadata );
        assertEquals( "Group Id", "org.apache.maven", metadata.getGroupId() );
        assertEquals( "Artifact Id", "samplejar", metadata.getArtifactId() );
        assertEquals( "Released Version", "2.0", metadata.getReleasedVersion() );
        assertEquals( "Latest Version", "6.0-SNAPSHOT", metadata.getLatestVersion() );
        assertEquals( "List of Available Versions", 18, metadata.getAvailableVersions().size() );
        assertTrue( "Available version 6.0-20060311.183228-10",
                    metadata.getAvailableVersions().contains( "6.0-20060311.183228-10" ) );
        assertTrue( "Available version 6.0-SNAPSHOT", metadata.getAvailableVersions().contains( "6.0-SNAPSHOT" ) );
    }
}
