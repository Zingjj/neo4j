/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.commandline.dbms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

import org.neo4j.commandline.admin.CommandFailed;
import org.neo4j.commandline.admin.CommandLocator;
import org.neo4j.commandline.admin.IncorrectUsage;
import org.neo4j.commandline.admin.Usage;
import org.neo4j.dbms.archive.Dumper;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.storemigration.StoreFileType;
import org.neo4j.kernel.impl.transaction.state.DataSourceManager;
import org.neo4j.kernel.internal.locker.StoreLocker;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.TestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.neo4j.dbms.archive.TestUtils.withPermissions;
import static org.neo4j.graphdb.factory.GraphDatabaseSettings.data_directory;
import static org.neo4j.graphdb.factory.GraphDatabaseSettings.logical_logs_location;

@ExtendWith( TestDirectoryExtension.class )
class DumpCommandTest
{
    @Inject
    private TestDirectory testDirectory;

    private Path homeDir;
    private Path configDir;
    private Path archive;
    private Dumper dumper;
    private Path databaseDirectory;

    @BeforeEach
    void setUp() throws Exception
    {
        homeDir = testDirectory.directory( "home-dir" ).toPath();
        configDir = testDirectory.directory( "config-dir" ).toPath();
        archive = testDirectory.file( "some-archive.dump" ).toPath();
        dumper = mock( Dumper.class );
        putStoreInDirectory( homeDir.resolve( "data/databases/foo.db" ) );
        databaseDirectory = homeDir.resolve( "data/databases/foo.db" );
    }

    @Test
    void shouldDumpTheDatabaseToTheArchive() throws Exception
    {
        execute( "foo.db" );
        verify( dumper ).dump( eq( homeDir.resolve( "data/databases/foo.db" ) ),
                eq( homeDir.resolve( "data/databases/foo.db" ) ), eq( archive ), any() );
    }

    @Test
    void shouldCalculateTheDatabaseDirectoryFromConfig() throws Exception
    {
        Path dataDir = testDirectory.directory( "some-other-path" ).toPath();
        Path databaseDir = dataDir.resolve( "databases/foo.db" );
        putStoreInDirectory( databaseDir );
        Files.write( configDir.resolve( Config.DEFAULT_CONFIG_FILE_NAME ), singletonList( formatProperty( data_directory, dataDir ) ) );

        execute( "foo.db" );
        verify( dumper ).dump( eq( databaseDir ), eq( databaseDir ), any(), any() );
    }

    @Test
    void shouldCalculateTheTxLogDirectoryFromConfig() throws Exception
    {
        Path dataDir = testDirectory.directory( "some-other-path" ).toPath();
        Path txLogsDir = testDirectory.directory( "txLogsPath" ).toPath();
        Path databaseDir = dataDir.resolve( "databases/foo.db" );
        putStoreInDirectory( databaseDir );
        Files.write( configDir.resolve( Config.DEFAULT_CONFIG_FILE_NAME ),
                asList( formatProperty( data_directory, dataDir ),
                        formatProperty( logical_logs_location, txLogsDir ) ) );

        execute( "foo.db" );
        verify( dumper ).dump( eq( databaseDir ), eq( txLogsDir ), any(), any() );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void shouldHandleDatabaseSymlink() throws Exception
    {
        Path symDir = testDirectory.directory( "path-to-links" ).toPath();
        Path realDatabaseDir = symDir.resolve( "foo.db" );

        Path dataDir = testDirectory.directory( "some-other-path" ).toPath();
        Path databaseDir = dataDir.resolve( "databases/foo.db" );

        putStoreInDirectory( realDatabaseDir );
        Files.createDirectories( dataDir.resolve( "databases" ) );

        Files.createSymbolicLink( databaseDir, realDatabaseDir );
        Files.write( configDir.resolve( Config.DEFAULT_CONFIG_FILE_NAME ),
                singletonList( format( "%s=%s", data_directory.name(), dataDir.toString().replace( '\\', '/' ) ) ) );

        execute( "foo.db" );
        verify( dumper ).dump( eq( realDatabaseDir ), eq( realDatabaseDir ), any(), any() );
    }

    @Test
    void shouldCalculateTheArchiveNameIfPassedAnExistingDirectory() throws Exception
    {
        File to = testDirectory.directory( "some-dir" );
        new DumpCommand( homeDir, configDir, dumper ).execute( new String[]{"--database=" + "foo.db", "--to=" + to} );
        verify( dumper ).dump( any( Path.class ), any( Path.class ), eq( to.toPath().resolve( "foo.db.dump" ) ), any() );
    }

    @Test
    void shouldConvertToCanonicalPath() throws Exception
    {
        new DumpCommand( homeDir, configDir, dumper )
                .execute( new String[]{"--database=" + "foo.db", "--to=foo.dump"} );
        verify( dumper ).dump( any( Path.class ), any( Path.class ),
                eq( Paths.get( new File( "foo.dump" ).getCanonicalPath() ) ), any() );
    }

    @Test
    void shouldNotCalculateTheArchiveNameIfPassedAnExistingFile()
            throws Exception
    {
        Files.createFile( archive );
        execute( "foo.db" );
        verify( dumper ).dump( any(), any(), eq( archive ), any() );
    }

    @Test
    void shouldRespectTheStoreLock() throws Exception
    {
        Path databaseDirectory = homeDir.resolve( "data/databases/foo.db" );

        try ( FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction();
              StoreLocker storeLocker = new StoreLocker( fileSystem, databaseDirectory.toFile() ) )
        {
            storeLocker.checkLock();

            CommandFailed commandFailed = assertThrows( CommandFailed.class, () -> execute( "foo.db" ) );
            assertEquals( "the database is in use -- stop Neo4j and try again", commandFailed.getMessage() );
        }
    }

    @Test
    void shouldReleaseTheStoreLockAfterDumping() throws Exception
    {
        execute( "foo.db" );
        assertCanLockStore( databaseDirectory );
    }

    @Test
    void shouldReleaseTheStoreLockEvenIfThereIsAnError() throws Exception
    {
        doThrow( IOException.class ).when( dumper ).dump( any(), any(), any(), any() );
        assertThrows( CommandFailed.class, () -> execute( "foo.db" ) );
        assertCanLockStore( databaseDirectory );
    }

    @Test
    void shouldNotAccidentallyCreateTheDatabaseDirectoryAsASideEffectOfStoreLocking()
            throws Exception
    {
        Path databaseDirectory = homeDir.resolve( "data/databases/accident.db" );

        doAnswer( ignored ->
        {
            assertThat( Files.exists( databaseDirectory ), equalTo( false ) );
            return null;
        } ).when( dumper ).dump( any(), any(), any(), any() );

        execute( "foo.db" );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void shouldReportAHelpfulErrorIfWeDontHaveWritePermissionsForLock() throws Exception
    {
        try ( FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction();
              StoreLocker storeLocker = new StoreLocker( fileSystem, databaseDirectory.toFile() ) )
        {
            storeLocker.checkLock();

            try ( Closeable ignored = withPermissions( databaseDirectory.resolve( StoreLocker.STORE_LOCK_FILENAME ),
                    emptySet() ) )
            {
                CommandFailed commandFailed = assertThrows( CommandFailed.class, () -> execute( "foo.db" ) );
                assertEquals( commandFailed.getMessage(), "you do not have permission to dump the database -- is Neo4j running as a different user?" );
            }
        }
    }

    @Test
    void shouldExcludeTheStoreLockFromTheArchiveToAvoidProblemsWithReadingLockedFilesOnWindows()
            throws Exception
    {
        doAnswer( invocation ->
        {
            Predicate<Path> exclude = invocation.getArgument( 3 );
            assertThat( exclude.test( Paths.get( StoreLocker.STORE_LOCK_FILENAME ) ), is( true ) );
            assertThat( exclude.test( Paths.get( "some-other-file" ) ), is( false ) );
            return null;
        } ).when( dumper ).dump(any(), any(), any(), any() );

        execute( "foo.db" );
    }

    @Test
    void shouldDefaultToGraphDB() throws Exception
    {
        Path dataDir = testDirectory.directory( "some-other-path" ).toPath();
        Path databaseDir = dataDir.resolve( "databases/" + DataSourceManager.DEFAULT_DATABASE_NAME );
        putStoreInDirectory( databaseDir );
        Files.write( configDir.resolve( Config.DEFAULT_CONFIG_FILE_NAME ), singletonList( formatProperty( data_directory, dataDir ) ) );

        new DumpCommand( homeDir, configDir, dumper ).execute( new String[]{"--to=" + archive} );
        verify( dumper ).dump( eq( databaseDir ), eq( databaseDir ), any(), any() );
    }

    @Test
    void shouldObjectIfTheArchiveArgumentIsMissing()
    {

        IllegalArgumentException exception = assertThrows( IllegalArgumentException.class,
                () -> new DumpCommand( homeDir, configDir, null ).execute( new String[]{"--database=something"} ) );
        assertEquals( "Missing argument 'to'", exception.getMessage() );
    }

    @Test
    void shouldGiveAClearErrorIfTheArchiveAlreadyExists() throws Exception
    {
        doThrow( new FileAlreadyExistsException( "the-archive-path" ) ).when( dumper ).dump( any(), any(), any(), any() );
        CommandFailed commandFailed = assertThrows( CommandFailed.class, () -> execute( "foo.db" ) );
        assertEquals( "archive already exists: the-archive-path", commandFailed.getMessage() );
    }

    @Test
    void shouldGiveAClearMessageIfTheDatabaseDoesntExist()
    {
        CommandFailed commandFailed = assertThrows( CommandFailed.class, () -> execute( "bobo.db" ) );
        assertEquals( "database does not exist: bobo.db", commandFailed.getMessage() );
    }

    @Test
    void shouldGiveAClearMessageIfTheArchivesParentDoesntExist() throws Exception
    {
        doThrow( new NoSuchFileException( archive.getParent().toString() ) ).when( dumper ).dump(any(), any(), any(), any() );
        CommandFailed commandFailed = assertThrows( CommandFailed.class, () -> execute( "foo.db" ) );
        assertEquals( "unable to dump database: NoSuchFileException: " + archive.getParent(), commandFailed.getMessage() );
    }

    @Test
    void shouldWrapIOExceptionsCarefullyBecauseCriticalInformationIsOftenEncodedInTheirNameButMissingFromTheirMessage()
            throws Exception
    {
        doThrow( new IOException( "the-message" ) ).when( dumper ).dump(any(), any(), any(), any() );
        CommandFailed commandFailed = assertThrows( CommandFailed.class, () -> execute( "foo.db" ) );
        assertEquals( "unable to dump database: IOException: the-message", commandFailed.getMessage() );
    }

    @Test
    void shouldPrintNiceHelp() throws Exception
    {
        try ( ByteArrayOutputStream baos = new ByteArrayOutputStream() )
        {
            PrintStream ps = new PrintStream( baos );

            Usage usage = new Usage( "neo4j-admin", mock( CommandLocator.class ) );
            usage.printUsageForCommand( new DumpCommandProvider(), ps::println );

            assertEquals( String.format( "usage: neo4j-admin dump [--database=<name>] --to=<destination-path>%n" +
                            "%n" +
                            "environment variables:%n" +
                            "    NEO4J_CONF    Path to directory which contains neo4j.conf.%n" +
                            "    NEO4J_DEBUG   Set to anything to enable debug output.%n" +
                            "    NEO4J_HOME    Neo4j home directory.%n" +
                            "    HEAP_SIZE     Set JVM maximum heap size during command execution.%n" +
                            "                  Takes a number and a unit, for example 512m.%n" +
                            "%n" +
                            "Dump a database into a single-file archive. The archive can be used by the load%n" +
                            "command. <destination-path> can be a file or directory (in which case a file%n" +
                            "called <database>.dump will be created). It is not possible to dump a database%n" +
                            "that is mounted in a running Neo4j server.%n" +
                            "%n" +
                            "options:%n" +
                            "  --database=<name>         Name of database. [default:" + DataSourceManager.DEFAULT_DATABASE_NAME + "]%n" +
                            "  --to=<destination-path>   Destination (file or folder) of database dump.%n" ),
                    baos.toString() );
        }
    }

    private void execute( final String database ) throws IncorrectUsage, CommandFailed
    {
        new DumpCommand( homeDir, configDir, dumper )
                .execute( new String[]{"--database=" + database, "--to=" + archive} );
    }

    private static void assertCanLockStore( Path databaseDirectory ) throws IOException
    {
        try ( FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction();
              StoreLocker storeLocker = new StoreLocker( fileSystem, databaseDirectory.toFile() ) )
        {
            storeLocker.checkLock();
        }
    }

    private static void putStoreInDirectory( Path storeDir ) throws IOException
    {
        Files.createDirectories( storeDir );
        Path storeFile = storeDir.resolve( StoreFileType.STORE.augment( MetaDataStore.DEFAULT_NAME ) );
        Files.createFile( storeFile );
    }

    private static String formatProperty( Setting setting, Path path )
    {
        return format( "%s=%s", setting.name(), path.toString().replace( '\\', '/' ) );
    }
}
