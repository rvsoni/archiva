package org.apache.archiva.proxy;

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

import org.apache.archiva.common.filelock.DefaultFileLockManager;
import org.apache.archiva.common.utils.VersionUtil;
import org.apache.archiva.configuration.ProxyConnectorConfiguration;
import org.apache.archiva.model.ArchivaRepositoryMetadata;
import org.apache.archiva.model.Plugin;
import org.apache.archiva.model.SnapshotVersion;
import org.apache.archiva.policies.CachedFailuresPolicy;
import org.apache.archiva.policies.ChecksumPolicy;
import org.apache.archiva.policies.ReleasesPolicy;
import org.apache.archiva.policies.SnapshotsPolicy;
import org.apache.archiva.repository.content.BaseRepositoryContentLayout;
import org.apache.archiva.repository.content.ContentItem;
import org.apache.archiva.repository.content.DataItem;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.Project;
import org.apache.archiva.repository.content.Version;
import org.apache.archiva.repository.content.base.ArchivaItemSelector;
import org.apache.archiva.repository.metadata.RepositoryMetadataException;
import org.apache.archiva.repository.metadata.base.MetadataTools;
import org.apache.archiva.repository.metadata.base.RepositoryMetadataWriter;
import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.archiva.repository.storage.fs.FilesystemStorage;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.wagon.TransferFailedException;
import org.easymock.EasyMock;
import org.junit.Test;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * MetadataTransferTest - Tests the various fetching / merging concepts surrounding the maven-metadata.xml files
 * present in the repository.
 * <p/>
 * Test Case Naming is as follows.
 * <p/>
 * <code>
 * public void testGet[Release|Snapshot|Project]Metadata[Not]Proxied[Not|On]Local[Not|On|Multiple]Remote
 * </code>
 * <p/>
 * <pre>
 * Which should leave the following matrix of test cases.
 *
 *   Metadata  | Proxied  | Local | Remote
 *   ----------+----------+-------+---------
 *   Release   | Not      | Not   | n/a (1)
 *   Release   | Not      | On    | n/a (1)
 *   Release   |          | Not   | Not
 *   Release   |          | Not   | On
 *   Release   |          | Not   | Multiple
 *   Release   |          | On    | Not
 *   Release   |          | On    | On
 *   Release   |          | On    | Multiple
 *   Snapshot  | Not      | Not   | n/a (1)
 *   Snapshot  | Not      | On    | n/a (1)
 *   Snapshot  |          | Not   | Not
 *   Snapshot  |          | Not   | On
 *   Snapshot  |          | Not   | Multiple
 *   Snapshot  |          | On    | Not
 *   Snapshot  |          | On    | On
 *   Snapshot  |          | On    | Multiple
 *   Project   | Not      | Not   | n/a (1)
 *   Project   | Not      | On    | n/a (1)
 *   Project   |          | Not   | Not
 *   Project   |          | Not   | On
 *   Project   |          | Not   | Multiple
 *   Project   |          | On    | Not
 *   Project   |          | On    | On
 *   Project   |          | On    | Multiple
 *
 * (1) If it isn't proxied, no point in having a remote.
 * </pre>
 *
 *
 */
public class MetadataTransferTest
    extends AbstractProxyTestCase
{

    @Inject
    @Named(value = "metadataTools#mocked")
    private MetadataTools metadataTools;


    @Test
    public void testGetProjectMetadataProxiedNotLocalOnRemoteConnectoDisabled()
        throws Exception
    {
        // New project metadata that does not exist locally but exists on remote.
        String requestedResource = "org/apache/maven/test/get-found-in-proxy/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, true );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        Path expectedFile = managedDefaultDir.resolve(requestedResource);
        BaseRepositoryContentLayout layout = managedDefaultRepository.getLayout( BaseRepositoryContentLayout.class );
        ContentItem metaItem = managedDefaultRepository.toItem( requestedResource );
        Project project = layout.adaptItem( Project.class, managedDefaultRepository.getParent( metaItem ) );
        assertNotNull( project );
        String metaPath = managedDefaultRepository.toPath( layout.getMetadataItem( project ) );
        StorageAsset downloadedFile = proxyHandler.fetchMetadataFromProxies( managedDefaultRepository.getRepository(),
                                                                     metaPath ).getFile();

        assertNull( "Should not have downloaded a file.", downloadedFile );
        assertNoTempFiles( expectedFile );
    }

    // TODO: same test for other fetch* methods
    @Test
    public void testFetchFromTwoProxiesWhenFirstConnectionFails()
        throws Exception
    {
        // Project metadata that does not exist locally, but has multiple versions in remote repos
        String requestedResource = "org/apache/maven/test/get-default-layout/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        saveRemoteRepositoryConfig( "badproxied1", "Bad Proxied 1", "http://bad.machine.com/repo/", "default" );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, "badproxied1", ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( "badproxied1", requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // ensure that a hard failure in the first proxy connector is skipped and the second repository checked
        Path expectedFile = managedDefaultDir.resolve(
                                      metadataTools.getRepositorySpecificName( "badproxied1", requestedResource ) );

        wagonMock.get( EasyMock.eq( requestedResource ), EasyMock.anyObject( File.class ));
        EasyMock.expectLastCall().andThrow( new TransferFailedException( "can't connect" ) );


        wagonMockControl.replay();

        assertFetchProjectOrGroup( requestedResource );

        wagonMockControl.verify();

        assertProjectMetadataContents( requestedResource, new String[]{ "1.0.1" }, "1.0.1", "1.0.1" );
        assertNoRepoMetadata( "badproxied1", requestedResource );
        assertRepoProjectMetadata( ID_PROXIED2, requestedResource, new String[]{ "1.0.1" } );
    }

    /**
     * Attempt to get the project metadata for non-existant artifact.
     * <p/>
     * Expected result: the maven-metadata.xml file is not created on the managed repository, nor returned
     * to the requesting client.
     */
    @Test
    public void testGetProjectMetadataNotProxiedNotLocal()
        throws Exception
    {
        // The artifactId "get-default-metadata-nonexistant" does not exist (intentionally).
        String requestedResource = "org/apache/maven/test/get-default-metadata-nonexistant/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        config.getConfiguration().setProxyConnectors( new ArrayList<ProxyConnectorConfiguration>( ) );

        assertResourceNotFound( requestedResource );

        // No proxy setup, nothing fetched, failure expected.
        assertFetchProjectOrGroupFailed( requestedResource );

        // No local artifactId, and no fetch, should equal no metadata file downloaded / created / updated.
        assertResourceNotFound( requestedResource );
    }

    @Test
    public void testGetProjectMetadataNotProxiedOnLocal()
        throws Exception
    {

        // Project metadata that exists and has multiple versions
        String requestedResource = "org/apache/maven/test/get-project-metadata/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        config.getConfiguration().setProxyConnectors( new ArrayList<ProxyConnectorConfiguration>( ) );

        assertResourceExists( requestedResource );

        // No proxy setup, nothing fetched from remote, but local exists.
        assertFetchProjectOrGroup( requestedResource );

        // Nothing fetched.  Should only contain contents of what is in the repository.
        // A metadata update is not performed in this use case.  Local metadata content is only
        // updated via the metadata updater consumer.
        assertProjectMetadataContents( requestedResource, new String[]{ "1.0" }, null, null );
    }

    @Test
    public void testGetProjectMetadataProxiedNotLocalMultipleRemotes()
        throws Exception
    {
        // Project metadata that does not exist locally, but has multiple versions in remote repos
        String requestedResource = "org/apache/maven/test/get-default-layout/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, metadata fetched from both remotes.
        assertFetchProjectOrGroup( requestedResource );

        // Nothing fetched.  Should only contain contents of what is in the repository.
        assertProjectMetadataContents( requestedResource, new String[]{ "1.0", "1.0.1" }, "1.0.1", "1.0.1" );
        assertRepoProjectMetadata( ID_PROXIED1, requestedResource, new String[]{ "1.0" } );
        assertRepoProjectMetadata( ID_PROXIED2, requestedResource, new String[]{ "1.0.1" } );
    }

    @Test
    public void testGetProjectMetadataProxiedNotLocalNotRemote()
        throws Exception
    {
        // Non-existant project metadata that does not exist locally and doesn't exist on remotes.
        String requestedResource = "org/apache/maven/test/get-bogus-artifact/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, nothing fetched from remotes, local does not exist.
        assertFetchProjectOrGroupFailed( requestedResource );

        // Nothing fetched.  Nothing should exist.
        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );
    }

    @Test
    public void testGetProjectMetadataProxiedNotLocalOnRemote()
        throws Exception
    {
        // New project metadata that does not exist locally but exists on remote.
        String requestedResource = "org/apache/maven/test/get-found-in-proxy/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        // One proxy setup, metadata fetched from remote, local does not exist.
        assertFetchProjectOrGroup( requestedResource );

        // Remote fetched.  Local created/updated.
        assertProjectMetadataContents( requestedResource, new String[]{ "1.0.5" }, "1.0.5", "1.0.5" );
        assertRepoProjectMetadata( ID_PROXIED1, requestedResource, new String[]{ "1.0.5" } );
    }

    @Test
    public void testGetProjectMetadataProxiedOnLocalMultipleRemote()
        throws Exception
    {
        // Project metadata that exist locally, and has multiple versions in remote repos
        String requestedResource = "org/apache/maven/test/get-on-multiple-repos/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertProjectMetadataContents( requestedResource, new String[]{ "1.0" }, null, null );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, metadata fetched from both remotes.
        assertFetchProjectOrGroup( requestedResource );

        // metadata fetched from both repos, and merged with local version.
        assertProjectMetadataContents( requestedResource, new String[]{ "1.0", "1.0.1", "2.0" }, "2.0", "2.0" );
        assertRepoProjectMetadata( ID_PROXIED1, requestedResource, new String[]{ "1.0", "2.0" } );
        assertRepoProjectMetadata( ID_PROXIED2, requestedResource, new String[]{ "1.0", "1.0.1" } );
    }

    @Test
    public void testGetProjectMetadataProxiedOnLocalNotRemote()
        throws Exception
    {

        // Project metadata that exist locally, and does not exist in remote repos.
        String requestedResource = "org/apache/maven/test/get-not-on-remotes/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );


        config.getConfiguration().setProxyConnectors( new ArrayList<ProxyConnectorConfiguration>( ) );
        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertProjectMetadataContents( requestedResource, new String[]{ "1.0-beta-2" }, null, null );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, metadata fetch from remotes fail (because they dont exist).
        assertFetchProjectOrGroup( requestedResource );

        // metadata not fetched from both repos, and local version exists.
        // Since there was no updated metadata content from a remote/proxy, a metadata update on
        // the local file never ran.  Local only updates are performed via the metadata updater consumer.
        assertProjectMetadataContents( requestedResource, new String[]{ "1.0-beta-2" }, null, null );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );
    }

    @Test
    public void testGetProjectMetadataProxiedOnLocalOnRemote()
        throws Exception
    {
        // Project metadata that exist locally and exists on remote.
        String requestedResource = "org/apache/maven/test/get-on-local-on-remote/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertProjectMetadataContents( requestedResource, new String[]{ "1.0.8", "1.0.22" }, null, null );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        // One proxy setup, metadata fetched from remote, local exists.
        assertFetchProjectOrGroup( requestedResource );

        // Remote fetched.  Local updated.
        assertProjectMetadataContents( requestedResource, new String[]{ "1.0.8", "1.0.22", "2.0" }, "2.0", "2.0" );
        assertRepoProjectMetadata( ID_PROXIED1, requestedResource, new String[]{ "1.0.22", "2.0" } );
    }

    /**
     * A request for a release maven-metadata.xml file that does not exist locally, and the managed
     * repository has no proxied repositories set up.
     * <p/>
     * Expected result: the maven-metadata.xml file is not created on the managed repository, nor returned
     * to the requesting client.
     */
    @Test
    public void testGetReleaseMetadataNotProxiedNotLocal()
        throws Exception
    {
        // The artifactId "get-default-metadata-nonexistant" does not exist (intentionally).
        String requestedResource = "org/apache/maven/test/get-default-metadata-nonexistant/1.0/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        assertNoMetadata( requestedResource );

        // No proxy setup, nothing fetched, failure expected.
        assertFetchVersionedFailed( requestedResource );

        // No local artifactId, and no fetch, should equal no metadata file downloaded / created / updated.
        assertNoMetadata( requestedResource );
    }

    /**
     * A request for a maven-metadata.xml file that does exist locally, and the managed
     * repository has no proxied repositories set up.
     * <p/>
     * Expected result: the maven-metadata.xml file is updated locally, based off of the managed repository
     * information, and then returned to the client.
     */
    @Test
    public void testGetReleaseMetadataNotProxiedOnLocal()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-default-metadata/1.0/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        assertResourceExists( requestedResource );

        assertFetchVersioned( requestedResource );

        assertReleaseMetadataContents( requestedResource );
    }

    /**
     * A request for a release maven-metadata.xml file that does not exist on the managed repository, but
     * exists on multiple remote repositories.
     * <p/>
     * Expected result: the maven-metadata.xml file is downloaded from the remote into the repository specific
     * file location on the managed repository, a merge of the contents to the requested
     * maven-metadata.xml is performed, and then the merged maven-metadata.xml file is
     * returned to the client.
     */
    @Test
    public void testGetReleaseMetadataProxiedNotLocalMultipleRemotes()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-default-layout/1.0/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        assertFetchVersioned( requestedResource );

        assertReleaseMetadataContents( requestedResource );
        assertRepoReleaseMetadataContents( ID_PROXIED1, requestedResource );
        assertRepoReleaseMetadataContents( ID_PROXIED2, requestedResource );
    }

    /**
     * A request for a maven-metadata.xml file that does not exist locally, nor does it exist in a remote
     * proxied repository.
     * <p/>
     * Expected result: the maven-metadata.xml file is created locally, based off of managed repository
     * information, and then return to the client.
     */
    @Test
    public void testGetReleaseMetadataProxiedNotLocalNotRemote()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-bad-metadata/1.0/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );

        assertFetchProjectOrGroupFailed( requestedResource );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
    }

    /**
     * A request for a maven-metadata.xml file that does not exist on the managed repository, but
     * exists on 1 remote repository.
     * <p/>
     * Expected result: the maven-metadata.xml file is downloaded from the remote into the repository specific
     * file location on the managed repository, a merge of the contents to the requested
     * maven-metadata.xml is performed, and then the merged maven-metadata.xml file is
     * returned to the client.
     */
    @Test
    public void testGetReleaseMetadataProxiedNotLocalOnRemote()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-default-layout/1.0/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        assertFetchVersioned( requestedResource );

        assertReleaseMetadataContents( requestedResource );
        assertRepoReleaseMetadataContents( ID_PROXIED1, requestedResource );
    }

    /**
     * A request for a maven-metadata.xml file that exists in the managed repository, but
     * not on any remote repository.
     * <p/>
     * Expected result: the maven-metadata.xml file does not exist on the remote proxied repository and
     * is not downloaded.  There is no repository specific metadata file on the managed
     * repository.  The managed repository maven-metadata.xml is returned to the
     * client as-is.
     */
    @Test
    public void testGetReleaseMetadataProxiedOnLocalNotRemote()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-not-on-remotes/1.0-beta-2/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertReleaseMetadataContents( requestedResource );

        assertFetchVersioned( requestedResource );

        assertReleaseMetadataContents( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
    }

    /**
     * A request for a maven-metadata.xml file that exists in the managed repository, and on multiple
     * remote repositories.
     * <p/>
     * Expected result: the maven-metadata.xml file on the remote proxied repository is downloaded
     * and merged into the contents of the existing managed repository copy of
     * the maven-metadata.xml file.
     */
    @Test
    public void testGetReleaseMetadataProxiedOnLocalMultipleRemote()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-on-multiple-repos/1.0/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertReleaseMetadataContents( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        assertFetchVersioned( requestedResource );

        assertReleaseMetadataContents( requestedResource );
        assertRepoReleaseMetadataContents( ID_PROXIED1, requestedResource );
        assertRepoReleaseMetadataContents( ID_PROXIED2, requestedResource );
    }

    /**
     * A request for a maven-metadata.xml file that exists in the managed repository, and on one
     * remote repository.
     * <p/>
     * Expected result: the maven-metadata.xml file on the remote proxied repository is downloaded
     * and merged into the contents of the existing managed repository copy of
     * the maven-metadata.xml file.
     */
    @Test
    public void testGetReleaseMetadataProxiedOnLocalOnRemote()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-on-local-on-remote/1.0.22/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertReleaseMetadataContents( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        assertFetchVersioned( requestedResource );

        assertReleaseMetadataContents( requestedResource );
        assertRepoReleaseMetadataContents( ID_PROXIED1, requestedResource );
    }

    @Test
    public void testGetSnapshotMetadataNotProxiedNotLocal()
        throws Exception
    {
        // The artifactId "get-default-metadata-nonexistant" does not exist (intentionally).
        String requestedResource =
            "org/apache/maven/test/get-default-metadata-nonexistant/1.0-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        assertNoMetadata( requestedResource );

        // No proxy setup, nothing fetched, no local file, failure expected.
        assertFetchVersionedFailed( requestedResource );

        // No local artifactId, and no fetch, should equal no metadata file downloaded / created / updated.
        assertNoMetadata( requestedResource );
    }

    @Test
    public void testGetSnapshotMetadataNotProxiedOnLocal()
        throws Exception
    {
        // The artifactId exists locally (but not on a remote repo)
        String requestedResource =
            "org/apache/maven/test/get-snapshot-on-local-not-remote/2.0-alpha-2-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        assertResourceExists( requestedResource );

        // No proxy setup, nothing fetched from remote, local file exists, fetch should succeed.
        assertFetchVersioned( requestedResource );

        // Local metadata exists, should be updated to reflect the latest release.
        assertSnapshotMetadataContents( requestedResource, "20070821", "220304", 2 );
    }

    @Test
    public void testGetSnapshotMetadataProxiedNotLocalMultipleRemotes()
        throws Exception
    {
        String requestedResource =
            "org/apache/maven/test/get-timestamped-snapshot-in-both/1.0-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Proxying 2 repos, both have content, local file updated.
        assertFetchVersioned( requestedResource );

        assertSnapshotMetadataContents( requestedResource, "20070101", "000103", 2 );
        assertRepoSnapshotMetadataContents( ID_PROXIED1, requestedResource, "20061227", "112101", 2 );
        assertRepoSnapshotMetadataContents( ID_PROXIED2, requestedResource, "20070101", "000103", 2 );
    }

    @Test
    public void testGetSnapshotMetadataProxiedNotLocalNotRemote()
        throws Exception
    {
        // The artifactId "get-default-metadata-nonexistant" does not exist (intentionally).
        String requestedResource =
            "org/apache/maven/test/get-default-metadata-nonexistant/1.0-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertNoMetadata( requestedResource );

        // One proxy setup, nothing fetched, no local file, failure expected.
        assertFetchVersionedFailed( requestedResource );

        // No local artifactId, and no fetch, should equal no metadata file downloaded / created / updated.
        assertNoMetadata( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
    }

    @Test
    public void testGetSnapshotMetadataProxiedNotLocalOnRemote()
        throws Exception
    {
        // Artifact exists only in the proxied1 location.
        String requestedResource = "org/apache/maven/test/get-metadata-snapshot/1.0-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );

        // One proxy setup, one metadata fetched, local file created/updated.
        assertFetchVersioned( requestedResource );

        // Local artifact Id should contain latest (which in this case is from proxied download)
        assertSnapshotMetadataContents( requestedResource, "20050831", "101112", 1 );
        assertRepoSnapshotMetadataContents( ID_PROXIED1, requestedResource, "20050831", "101112", 1 );
    }

    @Test
    public void testGetSnapshotMetadataProxiedOnLocalMultipleRemote()
        throws Exception
    {
        String requestedResource = "org/apache/maven/test/get-snapshot-popular/2.0-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertSnapshotMetadataContents( requestedResource, "20070822", "021008", 3 );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Proxying 2 repos, both have content, local file updated.
        assertFetchVersioned( requestedResource );

        assertSnapshotMetadataContents( requestedResource, "20070823", "212711", 6 );
        assertRepoSnapshotMetadataContents( ID_PROXIED1, requestedResource, "20070822", "145534", 9 );
        assertRepoSnapshotMetadataContents( ID_PROXIED2, requestedResource, "20070823", "212711", 6 );
    }

    @Test
    public void testGetSnapshotMetadataProxiedOnLocalNotRemote()
        throws Exception
    {
        // The artifactId exists locally (but not on a remote repo)
        String requestedResource =
            "org/apache/maven/test/get-snapshot-on-local-not-remote/2.0-alpha-2-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceExists( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // two proxies setup, nothing fetched from either remote, local file exists, fetch should succeed.
        assertFetchVersioned( requestedResource );

        // Local metadata exists, repo metadatas should not exist, local file updated.
        assertSnapshotMetadataContents( requestedResource, "20070821", "220304", 2 );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );
    }

    @Test
    public void testGetSnapshotMetadataProxiedOnLocalOnRemote()
        throws Exception
    {
        // The artifactId exists locally (but not on a remote repo)
        String requestedResource =
            "org/apache/maven/test/get-present-metadata-snapshot/1.0-SNAPSHOT/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertSnapshotMetadataContents( requestedResource, "20050831", "101112", 1 );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        // two proxies setup, nothing fetched from either remote, local file exists, fetch should succeed.
        assertFetchVersioned( requestedResource );

        // Local metadata exists, repo metadata exists, local file updated.
        assertSnapshotMetadataContents( requestedResource, "20050831", "101112", 1 );
        assertRepoSnapshotMetadataContents( ID_PROXIED1, requestedResource, "20050831", "101112", 1 );
    }

    @Test
    public void testGetGroupMetadataNotProxiedNotLocal()
        throws Exception
    {
        // The artifactId "get-default-metadata-nonexistant" does not exist (intentionally).
        String requestedResource = "org/apache/maven/test/groups/get-default-metadata-nonexistant/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        assertResourceNotFound( requestedResource );

        // No proxy setup, nothing fetched, failure expected.
        assertFetchProjectOrGroupFailed( requestedResource );

        // No local artifactId, and no fetch, should equal no metadata file downloaded / created / updated.
        assertResourceNotFound( requestedResource );
    }

    @Test
    public void testGetGroupMetadataNotProxiedOnLocal()
        throws Exception
    {
        // Project metadata that exists and has multiple versions
        String requestedResource = "org/apache/maven/test/groups/get-project-metadata/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        assertResourceExists( requestedResource );

        // No proxy setup, nothing fetched from remote, but local exists.
        assertFetchProjectOrGroup( requestedResource );

        // Nothing fetched.  Should only contain contents of what is in the repository.
        // A metadata update is not performed in this use case.  Local metadata content is only
        // updated via the metadata updater consumer.
        assertGroupMetadataContents( requestedResource, new String[]{ "plugin1" } );
    }

    @Test
    public void testGetGroupMetadataProxiedNotLocalMultipleRemotes()
        throws Exception
    {
        // Project metadata that does not exist locally, but has multiple versions in remote repos
        String requestedResource = "org/apache/maven/test/groups/get-default-layout/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, metadata fetched from both remotes.
        assertFetchProjectOrGroup( requestedResource );

        // Nothing fetched.  Should only contain contents of what is in the repository.
        assertGroupMetadataContents( requestedResource, new String[]{ "plugin2", "plugin1" } );
        assertRepoGroupMetadataContents( ID_PROXIED1, requestedResource, new String[]{ "plugin1" } );
        assertRepoGroupMetadataContents( ID_PROXIED2, requestedResource, new String[]{ "plugin2" } );
    }

    @Test
    public void testGetGroupsMetadataProxiedNotLocalNotRemote()
        throws Exception
    {
        // Non-existant project metadata that does not exist locally and doesn't exist on remotes.
        String requestedResource = "org/apache/maven/test/groups/get-bogus-artifact/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, nothing fetched from remotes, local does not exist.
        assertFetchProjectOrGroupFailed( requestedResource );

        // Nothing fetched.  Nothing should exist.
        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );
    }

    @Test
    public void testGetGroupMetadataProxiedNotLocalOnRemote()
        throws Exception
    {
        // New project metadata that does not exist locally but exists on remote.
        String requestedResource = "org/apache/maven/test/groups/get-found-in-proxy/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertResourceNotFound( requestedResource );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        // One proxy setup, metadata fetched from remote, local does not exist.
        assertFetchProjectOrGroup( requestedResource );

        // Remote fetched.  Local created/updated.
        assertGroupMetadataContents( requestedResource, new String[]{ "plugin3" } );
        assertRepoGroupMetadataContents( ID_PROXIED1, requestedResource, new String[]{ "plugin3" } );
    }

    @Test
    public void testGetGroupMetadataProxiedOnLocalMultipleRemote()
        throws Exception
    {
        // Project metadata that exist locally, and has multiple versions in remote repos
        String requestedResource = "org/apache/maven/test/groups/get-on-multiple-repos/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertGroupMetadataContents( requestedResource, new String[]{ "plugin1" } );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, metadata fetched from both remotes.
        assertFetchProjectOrGroup( requestedResource );

        // metadata fetched from both repos, and merged with local version.
        assertGroupMetadataContents( requestedResource, new String[]{ "plugin1", "plugin2", "plugin4" } );
        assertRepoGroupMetadataContents( ID_PROXIED1, requestedResource, new String[]{ "plugin1", "plugin4" } );
        assertRepoGroupMetadataContents( ID_PROXIED2, requestedResource, new String[]{ "plugin1", "plugin2" } );
    }

    @Test
    public void testGetGroupMetadataProxiedOnLocalNotRemote()
        throws Exception
    {
        // Project metadata that exist locally, and does not exist in remote repos.
        String requestedResource = "org/apache/maven/test/groups/get-not-on-remotes/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED2, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertGroupMetadataContents( requestedResource, new String[]{ "plugin5" } );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );

        // Two proxies setup, metadata fetch from remotes fail (because they dont exist).
        assertFetchProjectOrGroup( requestedResource );

        // metadata not fetched from both repos, and local version exists.
        // Since there was no updated metadata content from a remote/proxy, a metadata update on
        // the local file never ran.  Local only updates are performed via the metadata updater consumer.
        assertGroupMetadataContents( requestedResource, new String[]{ "plugin5" } );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );
        assertNoRepoMetadata( ID_PROXIED2, requestedResource );
    }

    @Test
    public void testGetGroupMetadataProxiedOnLocalOnRemote()
        throws Exception
    {
        // Project metadata that exist locally and exists on remote.
        String requestedResource = "org/apache/maven/test/groups/get-on-local-on-remote/maven-metadata.xml";
        setupTestableManagedRepository( requestedResource );

        // Configure Connector (usually done within archiva.xml configuration)
        saveConnector( ID_DEFAULT_MANAGED, ID_PROXIED1, ChecksumPolicy.FIX, ReleasesPolicy.ALWAYS,
                       SnapshotsPolicy.ALWAYS, CachedFailuresPolicy.NO, false );

        assertGroupMetadataContents( requestedResource, new String[]{ "plugin6", "plugin7" } );
        assertNoRepoMetadata( ID_PROXIED1, requestedResource );

        // One proxy setup, metadata fetched from remote, local exists.
        assertFetchProjectOrGroup( requestedResource );

        // Remote fetched.  Local updated.
        assertGroupMetadataContents( requestedResource, new String[]{ "plugin6", "plugin7", "plugin4" } );
        assertRepoGroupMetadataContents( ID_PROXIED1, requestedResource, new String[]{ "plugin7", "plugin4" } );
    }

    /**
     * Transfer the metadata file.
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertFetchProjectOrGroup( String requestedResource )
        throws Exception
    {
        Path expectedFile = managedDefaultDir.resolve(requestedResource);

        BaseRepositoryContentLayout layout = managedDefaultRepository.getLayout( BaseRepositoryContentLayout.class );
        ContentItem metaItem = managedDefaultRepository.toItem( requestedResource );
        Project project = layout.adaptItem( Project.class, managedDefaultRepository.getParent( metaItem ) );
        assertNotNull( project );
        String metaPath = managedDefaultRepository.toPath( layout.getMetadataItem( project ) );

        StorageAsset downloadedFile = proxyHandler.fetchMetadataFromProxies( managedDefaultRepository.getRepository(),
                                                                     metaPath ).getFile();

        assertNotNull( "Should have downloaded a file.", downloadedFile );
        assertNoTempFiles( expectedFile );
    }


    /**
     * Transfer the metadata file, not expected to succeed.
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertFetchProjectOrGroupFailed( String requestedResource )
        throws Exception
    {
        Path expectedFile = managedDefaultDir.resolve(requestedResource);

        BaseRepositoryContentLayout layout = managedDefaultRepository.getLayout( BaseRepositoryContentLayout.class );
        ContentItem metaItem = managedDefaultRepository.toItem( requestedResource );
        Project project = layout.adaptItem( Project.class, managedDefaultRepository.getParent( metaItem ) );
        assertNotNull( project );
        String metaPath = managedDefaultRepository.toPath( layout.getMetadataItem( project ) );
        StorageAsset downloadedFile = proxyHandler.fetchMetadataFromProxies( managedDefaultRepository.getRepository(),
                                                                     metaPath ).getFile();

        assertNull( downloadedFile );
        assertNoTempFiles( expectedFile );
    }

    /**
     * Transfer the metadata file.
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertFetchVersioned( String requestedResource )
        throws Exception
    {
        Path expectedFile = managedDefaultDir.resolve(requestedResource);

        ContentItem item = managedDefaultRepository.toItem( requestedResource );
        if (item instanceof DataItem) {
            item = managedDefaultRepository.getParent( item );
        }
        assertNotNull( item );
        BaseRepositoryContentLayout layout = managedDefaultRepository.getLayout( BaseRepositoryContentLayout.class );
        Version version = layout.adaptItem( Version.class, item );
        assertNotNull( version );
        String metaPath = managedDefaultRepository.toPath( layout.getMetadataItem(
            version ) );

        StorageAsset downloadedFile = proxyHandler.fetchMetadataFromProxies( managedDefaultRepository.getRepository(),
                                                                     metaPath).getFile();

        assertNotNull( "Should have downloaded a file.", downloadedFile );
        assertNoTempFiles( expectedFile );
    }


    /**
     * Transfer the metadata file, not expected to succeed.
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertFetchVersionedFailed( String requestedResource )
        throws Exception
    {
        Path expectedFile = managedDefaultDir.resolve(requestedResource);
        ContentItem item = managedDefaultRepository.toItem( requestedResource );
        assertNotNull( item );
        BaseRepositoryContentLayout layout = managedDefaultRepository.getLayout( BaseRepositoryContentLayout.class );
        Version version = layout.adaptItem( Version.class, item );
        assertNotNull( version );
        String metaPath = managedDefaultRepository.toPath( layout.getMetadataItem(
            version ) );
        assertNotNull( metaPath );
        StorageAsset downloadedFile = proxyHandler.fetchMetadataFromProxies( managedDefaultRepository.getRepository(),
                                                                     metaPath ).getFile();

        assertNull( downloadedFile );
        assertNoTempFiles( expectedFile );
    }

    /**
     * Test for the existance of the requestedResource in the default managed repository.
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertResourceExists( String requestedResource )
        throws Exception
    {
        Path actualFile = managedDefaultDir.resolve(requestedResource);
        assertTrue( "Resource should exist: " + requestedResource, Files.exists(actualFile) );
    }

    private void assertMetadataEquals( String expectedMetadataXml, Path actualFile )
        throws Exception
    {
        assertNotNull( "Actual File should not be null.", actualFile );

        assertTrue( "Actual file exists.", Files.exists(actualFile) );

        StringWriter actualContents = new StringWriter();
        FilesystemStorage fsStorage = new FilesystemStorage(actualFile.getParent(), new DefaultFileLockManager());
        StorageAsset actualFileAsset = fsStorage.getAsset(actualFile.getFileName().toString());
        ArchivaRepositoryMetadata metadata = metadataTools.getMetadataReader( null ).read( actualFileAsset );
        RepositoryMetadataWriter.write( metadata, actualContents );

        Diff detailedDiff = DiffBuilder.compare( expectedMetadataXml).withTest( actualContents.toString() ).checkForSimilar().build();
        if ( detailedDiff.hasDifferences() )
        {
            for ( Difference diff : detailedDiff.getDifferences() )
            {
                System.out.println( diff );
            }
            assertEquals( expectedMetadataXml, actualContents );
        }

        // assertEquals( "Check file contents.", expectedMetadataXml, actualContents );
    }

    /**
     * Ensures that the requested resource is not present in the managed repository.
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertNoMetadata( String requestedResource )
        throws Exception
    {
        Path expectedFile = managedDefaultDir.resolve(requestedResource);
        assertFalse( "metadata should not exist: " + expectedFile, Files.exists(expectedFile) );
    }

    /**
     * Ensures that the proxied repository specific maven metadata file does NOT exist in the
     * managed repository.
     *
     * @param proxiedRepoId     the proxied repository id to validate with.
     * @param requestedResource the resource requested.
     */
    private void assertNoRepoMetadata( String proxiedRepoId, String requestedResource )
    {
        String proxiedFile = metadataTools.getRepositorySpecificName( proxiedRepoId, requestedResource );

        Path actualFile = managedDefaultDir.resolve(proxiedFile);
        assertFalse( "Repo specific metadata should not exist: " + actualFile, Files.exists(actualFile) );
    }

    private void assertGroupMetadataContents( String requestedResource, String expectedPlugins[] )
        throws Exception
    {
        Path actualFile = managedDefaultDir.resolve(requestedResource);
        assertTrue( "Snapshot Metadata should exist: " + requestedResource, Files.exists(actualFile) );

        ItemSelector actualMetadata = createGroupSelector( requestedResource );

        assertGroupMetadata( actualFile, actualMetadata, expectedPlugins );
    }

    private ItemSelector createProjectSelector(String path) throws RepositoryMetadataException
    {
        return metadataTools.toProjectSelector( path );
    }

    private ItemSelector createVersionedSelector(String path) throws RepositoryMetadataException
    {
        return metadataTools.toVersionedSelector( path );
    }

    private ItemSelector createGroupSelector( String requestedResource )
        throws RepositoryMetadataException
    {
        ItemSelector projectSelector = createProjectSelector( requestedResource );
        ArchivaItemSelector.Builder projectReference = ArchivaItemSelector.builder( ).withSelector( projectSelector );
        projectReference.withNamespace( projectSelector.getNamespace() + "." + projectSelector.getProjectId() );
        projectReference.withArtifactId( null );
        projectReference.withProjectId( null );
        return projectReference.build();
    }

    private void assertRepoGroupMetadataContents( String proxiedRepoId, String requestedResource,
                                                  String expectedPlugins[] )
        throws Exception
    {
        String proxiedFile = metadataTools.getRepositorySpecificName( proxiedRepoId, requestedResource );

        Path actualFile = managedDefaultDir.resolve(proxiedFile);
        assertTrue( "Repo Specific Group Metadata should exist: " + requestedResource, Files.exists(actualFile) );

        ItemSelector actualMetadata = createGroupSelector( requestedResource );

        assertGroupMetadata( actualFile, actualMetadata, expectedPlugins );
    }

    private void assertGroupMetadata( Path actualFile, ItemSelector actualMetadata, String expectedPlugins[] )
        throws Exception
    {
        // Build expected metadata XML
        StringWriter expectedMetadataXml = new StringWriter();
        ArchivaRepositoryMetadata m = new ArchivaRepositoryMetadata();
        m.setGroupId( actualMetadata.getNamespace() );

        for ( String pluginId : expectedPlugins )
        {
            Plugin p = new Plugin();
            p.setPrefix( pluginId );
            p.setArtifactId( pluginId + "-maven-plugin" );
            p.setName( "The " + pluginId + " Plugin" );
            m.getPlugins().add( p );
        }

        RepositoryMetadataWriter.write( m, expectedMetadataXml );

        // Compare the file to the actual contents.
        assertMetadataEquals( expectedMetadataXml.toString(), actualFile );
    }

    /**
     * Test for the existance of the requestedResource in the default managed repository, and if it exists,
     * does it contain the specified list of expected versions?
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertProjectMetadataContents( String requestedResource, String expectedVersions[],
                                                String latestVersion, String releaseVersion )
        throws Exception
    {
        Path actualFile = managedDefaultDir.resolve(requestedResource);
        assertTrue( Files.exists(actualFile) );

        ItemSelector metadata = createProjectSelector( requestedResource );

        // Build expected metadata XML
        StringWriter expectedMetadataXml = new StringWriter();
        ArchivaRepositoryMetadata m = new ArchivaRepositoryMetadata();
        m.setGroupId( metadata.getNamespace() );
        m.setArtifactId( metadata.getArtifactId() );
        m.setLatestVersion( latestVersion );
        m.setReleasedVersion( releaseVersion );

        if ( expectedVersions != null )
        {
            m.getAvailableVersions().addAll( Arrays.asList( expectedVersions ) );
        }

        RepositoryMetadataWriter.write( m, expectedMetadataXml );

        // Compare the file to the actual contents.
        assertMetadataEquals( expectedMetadataXml.toString(), actualFile );
    }

    /**
     * Test for the existance of the requestedResource in the default managed repository, and if it exists,
     * does it contain the expected release maven-metadata.xml contents?
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertReleaseMetadataContents( String requestedResource )
        throws Exception
    {
        Path actualFile = managedDefaultDir.resolve(requestedResource);
        assertTrue( "Release Metadata should exist: " + requestedResource, Files.exists(actualFile) );

        ItemSelector metadata = createVersionedSelector( requestedResource );

        // Build expected metadata XML
        StringWriter expectedMetadataXml = new StringWriter();
        ArchivaRepositoryMetadata m = new ArchivaRepositoryMetadata();
        m.setGroupId( metadata.getNamespace() );
        m.setArtifactId( metadata.getArtifactId() );
        m.setVersion( metadata.getVersion() );
        RepositoryMetadataWriter.write( m, expectedMetadataXml );

        // Compare the file to the actual contents.
        assertMetadataEquals( expectedMetadataXml.toString(), actualFile );
    }

    /**
     * Test for the existance of the snapshot metadata in the default managed repository, and if it exists,
     * does it contain the expected release maven-metadata.xml contents?
     *
     * @param requestedResource   the requested resource
     * @param expectedDate        the date in "yyyyMMdd" format
     * @param expectedTime        the time in "hhmmss" format
     * @param expectedBuildnumber the build number
     * @throws Exception
     */
    private void assertSnapshotMetadataContents( String requestedResource, String expectedDate, String expectedTime,
                                                 int expectedBuildnumber )
        throws Exception
    {
        Path actualFile = managedDefaultDir.resolve(requestedResource);
        assertTrue( "Snapshot Metadata should exist: " + requestedResource, Files.exists(actualFile) );

        ItemSelector actualMetadata = createVersionedSelector( requestedResource );

        assertSnapshotMetadata( actualFile, actualMetadata, expectedDate, expectedTime, expectedBuildnumber );
    }

    /**
     * Test for the existance of the proxied repository specific snapshot metadata in the default managed
     * repository, and if it exists, does it contain the expected release maven-metadata.xml contents?
     *
     * @param proxiedRepoId       the repository id of the proxied repository.
     * @param requestedResource   the requested resource
     * @param expectedDate        the date in "yyyyMMdd" format
     * @param expectedTime        the time in "hhmmss" format
     * @param expectedBuildnumber the build number
     * @throws Exception
     */
    private void assertRepoSnapshotMetadataContents( String proxiedRepoId, String requestedResource,
                                                     String expectedDate, String expectedTime, int expectedBuildnumber )
        throws Exception
    {
        String proxiedFile = metadataTools.getRepositorySpecificName( proxiedRepoId, requestedResource );

        Path actualFile = managedDefaultDir.resolve(proxiedFile);
        assertTrue( "Repo Specific Snapshot Metadata should exist: " + requestedResource, Files.exists(actualFile) );

        ItemSelector actualMetadata = createVersionedSelector( requestedResource );

        assertSnapshotMetadata( actualFile, actualMetadata, expectedDate, expectedTime, expectedBuildnumber );
    }

    private void assertSnapshotMetadata( Path actualFile, ItemSelector actualMetadata, String expectedDate,
                                         String expectedTime, int expectedBuildnumber )
        throws RepositoryMetadataException, Exception
    {
        // Build expected metadata XML
        StringWriter expectedMetadataXml = new StringWriter();
        ArchivaRepositoryMetadata m = new ArchivaRepositoryMetadata();
        m.setGroupId( actualMetadata.getNamespace() );
        m.setArtifactId( actualMetadata.getArtifactId() );
        m.setVersion( VersionUtil.getBaseVersion( actualMetadata.getVersion() ) );

        m.setSnapshotVersion( new SnapshotVersion() );

        if ( StringUtils.isNotBlank( expectedDate ) && StringUtils.isNotBlank( expectedTime ) )
        {
            m.getSnapshotVersion().setTimestamp( expectedDate + "." + expectedTime );
        }

        m.getSnapshotVersion().setBuildNumber( expectedBuildnumber );

        m.setLastUpdated( expectedDate + expectedTime );

        RepositoryMetadataWriter.write( m, expectedMetadataXml );

        // Compare the file to the actual contents.
        assertMetadataEquals( expectedMetadataXml.toString(), actualFile );
    }

    /**
     * Ensures that the repository specific maven metadata file exists, and contains the appropriate
     * list of expected versions within.
     *
     * @param proxiedRepoId
     * @param requestedResource
     * @param expectedProxyVersions
     */
    private void assertRepoProjectMetadata( String proxiedRepoId, String requestedResource,
                                            String[] expectedProxyVersions )
        throws Exception
    {
        String proxiedFile = metadataTools.getRepositorySpecificName( proxiedRepoId, requestedResource );

        Path actualFile = managedDefaultDir.resolve(proxiedFile);
        assertTrue( Files.exists(actualFile) );

        ItemSelector metadata = createProjectSelector( requestedResource );

        // Build expected metadata XML
        StringWriter expectedMetadataXml = new StringWriter();
        ArchivaRepositoryMetadata m = new ArchivaRepositoryMetadata();
        m.setGroupId( metadata.getNamespace() );
        m.setArtifactId( metadata.getArtifactId() );

        if ( expectedProxyVersions != null )
        {
            m.getAvailableVersions().addAll( Arrays.asList( expectedProxyVersions ) );
        }

        RepositoryMetadataWriter.write( m, expectedMetadataXml );

        // Compare the file to the actual contents.
        assertMetadataEquals( expectedMetadataXml.toString(), actualFile );
    }

    /**
     * Ensures that the repository specific maven metadata file exists, and contains the appropriate
     * list of expected versions within.
     *
     * @param proxiedRepoId
     * @param requestedResource
     */
    private void assertRepoReleaseMetadataContents( String proxiedRepoId, String requestedResource )
        throws Exception
    {
        String proxiedFile = metadataTools.getRepositorySpecificName( proxiedRepoId, requestedResource );

        Path actualFile = managedDefaultDir.resolve(proxiedFile);
        assertTrue( "Release metadata for repo should exist: " + actualFile, Files.exists(actualFile) );

        ItemSelector metadata = createVersionedSelector( requestedResource );

        // Build expected metadata XML
        StringWriter expectedMetadataXml = new StringWriter();
        ArchivaRepositoryMetadata m = new ArchivaRepositoryMetadata();
        m.setGroupId( metadata.getNamespace() );
        m.setArtifactId( metadata.getArtifactId() );
        m.setVersion( metadata.getVersion() );
        RepositoryMetadataWriter.write( m, expectedMetadataXml );

        // Compare the file to the actual contents.
        assertMetadataEquals( expectedMetadataXml.toString(), actualFile );
    }

    /**
     * Test for the non-existance of the requestedResource in the default managed repository.
     *
     * @param requestedResource the requested resource
     * @throws Exception
     */
    private void assertResourceNotFound( String requestedResource )
        throws Exception
    {
        Path actualFile = managedDefaultDir.resolve(requestedResource);
        assertFalse( "Resource should not exist: " + requestedResource, Files.exists(actualFile) );
    }

}
