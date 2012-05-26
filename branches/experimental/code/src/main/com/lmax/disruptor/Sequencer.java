/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import com.lmax.disruptor.util.Util;


/**
 * Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s
 */
public class Sequencer
{
    /** Set to -1 as sequence starting point */
    public static final long INITIAL_CURSOR_VALUE = -1L;

    private final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private Sequence[] gatingSequences;

    private final ClaimStrategy claimStrategy;
    private final WaitStrategy waitStrategy;

    /**
     * Construct a Sequencer with the selected strategies.
     *
     * @param claimStrategy for those claiming sequences.
     * @param waitStrategy for those waiting on sequences.
     */
    public Sequencer(final ClaimStrategy claimStrategy, final WaitStrategy waitStrategy)
    {
        this.claimStrategy = claimStrategy;
        this.waitStrategy = waitStrategy;
    }

    /**
     * Set the sequences that will gate publishers to prevent the buffer wrapping.
     *
     * This method must be called prior to claiming sequences otherwise
     * a NullPointerException will be thrown.
     *
     * @param sequences to be to be gated on.
     */
    public void setGatingSequences(final Sequence... sequences)
    {
        this.gatingSequences = sequences;
    }

    /**
     * Create a {@link SequenceBarrier} that gates on the the cursor and a list of {@link Sequence}s
     *
     * @param sequencesToTrack this barrier will track
     * @return the barrier gated as required
     */
    public SequenceBarrier newBarrier(final Sequence... sequencesToTrack)
    {
        return new ProcessingSequenceBarrier(waitStrategy, cursor, sequencesToTrack);
    }

    /**
     * Create a new {@link BatchDescriptor} that is the minimum of the requested size
     * and the buffer size.
     *
     * @param size for the batch
     * @return the new {@link BatchDescriptor}
     */
    public BatchDescriptor newBatchDescriptor(final int size)
    {
        return new BatchDescriptor(Math.min(size, claimStrategy.getBufferSize()));
    }

    /**
     * The capacity of the data structure to hold entries.
     *
     * @return the size of the RingBuffer.
     */
    public int getBufferSize()
    {
        return claimStrategy.getBufferSize();
    }

    /**
     * Get the value of the cursor indicating the published sequence.
     *
     * @return value of the cursor for events that have been published.
     */
    public long getCursor()
    {
        return cursor.get();
    }

    /**
     * Has the buffer got capacity to allocate another sequence.  This is a concurrent
     * method so the response should only be taken as an indication of available capacity.
     *
     * @param availableCapacity in the buffer
     * @return true if the buffer has the capacity to allocate the next sequence otherwise false.
     */
    public boolean hasAvailableCapacity(final int availableCapacity)
    {
        return claimStrategy.hasAvailableCapacity(availableCapacity, gatingSequences);
    }

    /**
     * Claim the next event in sequence for publishing.
     *
     * @return the claimed sequence value
     */
    public long next()
    {
        if (null == gatingSequences)
        {
            throw new NullPointerException("gatingSequences must be set before claiming sequences");
        }

        return claimStrategy.incrementAndGet(gatingSequences);
    }
    
    /**
     * Attempt to claim the next event in sequence for publishing.  Will return the
     * number of the slot if there is at least <code>availableCapacity</code> slots
     * available.  
     * 
     * @param availableCapacity
     * @return the claimed sequence value
     * @throws InsufficientCapacityException
     */
    public long tryNext(int availableCapacity) throws InsufficientCapacityException
    {
        if (null == gatingSequences)
        {
            throw new NullPointerException("gatingSequences must be set before claiming sequences");
        }
        
        if (availableCapacity < 1)
        {
            throw new IllegalArgumentException("Available capacity must be greater than 0");
        }
        
        return claimStrategy.checkAndIncrement(availableCapacity, 1, gatingSequences);
    }

    /**
     * Claim the next batch of sequence numbers for publishing.
     *
     * @param batchDescriptor to be updated for the batch range.
     * @return the updated batchDescriptor.
     */
    public BatchDescriptor next(final BatchDescriptor batchDescriptor)
    {
        if (null == gatingSequences)
        {
            throw new NullPointerException("gatingSequences must be set before claiming sequences");
        }

        final long sequence = claimStrategy.incrementAndGet(batchDescriptor.getSize(), gatingSequences);
        batchDescriptor.setEnd(sequence);
        return batchDescriptor;
    }

    /**
     * Claim a specific sequence when only one publisher is involved.
     *
     * @param sequence to be claimed.
     * @return sequence just claimed.
     */
    public long claim(final long sequence)
    {
        if (null == gatingSequences)
        {
            throw new NullPointerException("gatingSequences must be set before claiming sequences");
        }

        claimStrategy.setSequence(sequence, gatingSequences);

        return sequence;
    }

    /**
     * Publish an event and make it visible to {@link EventProcessor}s
     *
     * @param sequence to be published
     */
    public void publish(final long sequence)
    {
        publish(sequence, 1);
    }

    /**
     * Publish the batch of events in sequence.
     *
     * @param batchDescriptor to be published.
     */
    public void publish(final BatchDescriptor batchDescriptor)
    {
        publish(batchDescriptor.getEnd(), batchDescriptor.getSize());
    }

    /**
     * Force the publication of a cursor sequence.
     *
     * Only use this method when forcing a sequence and you are sure only one publisher exists.
     * This will cause the cursor to advance to this sequence.
     *
     * @param sequence which is to be forced for publication.
     */
    public void forcePublish(final long sequence)
    {
        cursor.set(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    protected ClaimStrategy getClaimStrategy()
    {
        return claimStrategy;
    }

    private void publish(final long sequence, final int batchSize)
    {
        claimStrategy.serialisePublishing(sequence, cursor, batchSize);
        waitStrategy.signalAllWhenBlocking();
    }

    public long remainingCapacity()
    {
        long consumed = Util.getMinimumSequence(gatingSequences);
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }
}