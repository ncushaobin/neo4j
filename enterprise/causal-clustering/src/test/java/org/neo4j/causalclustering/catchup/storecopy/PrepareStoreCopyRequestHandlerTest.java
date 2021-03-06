/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering.catchup.storecopy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.neo4j.causalclustering.catchup.CatchupServerProtocol;
import org.neo4j.causalclustering.catchup.ResponseMessageType;
import org.neo4j.causalclustering.identity.StoreId;
import org.neo4j.kernel.NeoStoreDataSource;
import org.neo4j.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptor;
import org.neo4j.kernel.impl.transaction.log.checkpoint.CheckPointer;
import org.neo4j.kernel.impl.transaction.log.checkpoint.StoreCopyCheckPointMutex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PrepareStoreCopyRequestHandlerTest
{
    private static final StoreId STORE_ID_MATCHING = new StoreId( 1, 2, 3, 4 );
    private static final StoreId STORE_ID_MISMATCHING = new StoreId( 5000, 6000, 7000, 8000 );
    private final ChannelHandlerContext channelHandlerContext = mock( ChannelHandlerContext.class );
    private EmbeddedChannel embeddedChannel;

    private static final CheckPointer checkPointer = mock( CheckPointer.class );
    private static final NeoStoreDataSource neoStoreDataSource = mock( NeoStoreDataSource.class );
    private final CatchupServerProtocol catchupServerProtocol = new CatchupServerProtocol();
    private final PrepareStoreCopyFiles prepareStoreCopyFiles = mock( PrepareStoreCopyFiles.class );

    @Before
    public void setup()
    {
        StoreCopyCheckPointMutex storeCopyCheckPointMutex = new StoreCopyCheckPointMutex();
        PrepareStoreCopyRequestHandler subject = createHandler( storeCopyCheckPointMutex );
        embeddedChannel = new EmbeddedChannel( subject );
    }

    private PrepareStoreCopyRequestHandler createHandler( StoreCopyCheckPointMutex storeCopyCheckPointMutex )
    {
        Supplier<CheckPointer> checkPointerSupplier = () -> checkPointer;
        Supplier<NeoStoreDataSource> dataSourceSupplier = () -> neoStoreDataSource;
        when( neoStoreDataSource.getStoreId() ).thenReturn( new org.neo4j.kernel.impl.store.StoreId( 1, 2, 5, 3, 4 ) );

        PrepareStoreCopyFilesProvider prepareStoreCopyFilesProvider = mock( PrepareStoreCopyFilesProvider.class );
        when( prepareStoreCopyFilesProvider.prepareStoreCopyFiles( any() ) ).thenReturn( prepareStoreCopyFiles );

        return new PrepareStoreCopyRequestHandler( catchupServerProtocol, checkPointerSupplier, storeCopyCheckPointMutex, dataSourceSupplier,
                prepareStoreCopyFilesProvider );
    }

    @Test
    public void shouldGiveErrorResponseIfStoreMismatch()
    {
        // given store id doesn't match

        // when PrepareStoreCopyRequest is written to channel
        embeddedChannel.writeInbound( new PrepareStoreCopyRequest( STORE_ID_MISMATCHING ) );

        // then there is a store id mismatch message
        assertEquals( ResponseMessageType.PREPARE_STORE_COPY_RESPONSE, embeddedChannel.readOutbound() );
        PrepareStoreCopyResponse response = PrepareStoreCopyResponse.error( PrepareStoreCopyResponse.Status.E_STORE_ID_MISMATCH );
        assertEquals( response, embeddedChannel.readOutbound() );

        // and the expected message type is reset back to message type
        assertTrue( catchupServerProtocol.isExpecting( CatchupServerProtocol.State.MESSAGE_TYPE ) );
    }

    @Test
    public void shouldGetSuccessfulResponseFromPrepareStoreCopyRequest() throws Exception
    {
        // given storeId matches
        IndexDescriptor[] descriptors = new IndexDescriptor[]{new IndexDescriptor( new LabelSchemaDescriptor( 1, 2, 3 ), IndexDescriptor.Type.GENERAL )};
        File[] files = new File[]{new File( "file" )};
        long lastCheckpoint = 1;

        configureProvidedStoreCopyFiles( new StoreResource[0], files, descriptors, lastCheckpoint );

        // when store listing is requested
        embeddedChannel.writeInbound( channelHandlerContext, new PrepareStoreCopyRequest( STORE_ID_MATCHING ) );

        // and the contents of the store listing response is sent
        assertEquals( ResponseMessageType.PREPARE_STORE_COPY_RESPONSE, embeddedChannel.readOutbound() );
        PrepareStoreCopyResponse response = PrepareStoreCopyResponse.success( files, descriptors, lastCheckpoint );
        assertEquals( response, embeddedChannel.readOutbound() );

        // and the protocol is reset to expect any message type after listing has been transmitted
        assertTrue( catchupServerProtocol.isExpecting( CatchupServerProtocol.State.MESSAGE_TYPE ) );
    }

    @Test
    public void shouldRetainLockWhileStreaming() throws Exception
    {
        // given
        ChannelPromise channelPromise = embeddedChannel.newPromise();
        ChannelHandlerContext channelHandlerContext = mock( ChannelHandlerContext.class );
        when( channelHandlerContext.writeAndFlush( any( PrepareStoreCopyResponse.class ) ) ).thenReturn( channelPromise );

        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        PrepareStoreCopyRequestHandler subjectHandler = createHandler( new StoreCopyCheckPointMutex( lock ) );

        // and
        IndexDescriptor[] descriptors = new IndexDescriptor[]{new IndexDescriptor( new LabelSchemaDescriptor( 1, 2, 3 ), IndexDescriptor.Type.GENERAL )};
        File[] files = new File[]{new File( "file" )};
        long lastCheckpoint = 1;
        configureProvidedStoreCopyFiles( new StoreResource[0], files, descriptors, lastCheckpoint );

        // when
        subjectHandler.channelRead0( channelHandlerContext, new PrepareStoreCopyRequest( STORE_ID_MATCHING ) );

        // then
        assertEquals( 1, lock.getReadLockCount() );

        // when
        channelPromise.setSuccess();

        //then
        assertEquals( 0, lock.getReadLockCount() );
    }

    private void configureProvidedStoreCopyFiles( StoreResource[] atomicFiles, File[] files, IndexDescriptor[] descriptors, long lastCommitedTx )
            throws IOException
    {
        when( prepareStoreCopyFiles.getAtomicFilesSnapshot() ).thenReturn( atomicFiles );
        when( prepareStoreCopyFiles.getIndexDescriptors() ).thenReturn( descriptors );
        when( prepareStoreCopyFiles.listReplayableFiles() ).thenReturn( files );
        when( checkPointer.lastCheckPointedTransactionId() ).thenReturn( lastCommitedTx );
    }
}
