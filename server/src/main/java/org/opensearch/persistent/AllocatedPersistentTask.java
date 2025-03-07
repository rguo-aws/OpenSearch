/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.persistent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.action.ActionListener;
import org.opensearch.action.admin.cluster.node.tasks.cancel.CancelTasksRequest;
import org.opensearch.common.Nullable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.tasks.CancellableTask;
import org.opensearch.tasks.TaskId;
import org.opensearch.tasks.TaskManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Represents a executor node operation that corresponds to a persistent task
 */
public class AllocatedPersistentTask extends CancellableTask {

    private static final Logger logger = LogManager.getLogger(AllocatedPersistentTask.class);
    private final AtomicReference<State> state;

    private volatile String persistentTaskId;
    private volatile long allocationId;
    private volatile @Nullable Exception failure;
    private volatile PersistentTasksService persistentTasksService;
    private volatile TaskManager taskManager;

    public AllocatedPersistentTask(long id, String type, String action, String description, TaskId parentTask,
                                   Map<String, String> headers) {
        super(id, type, action, description, parentTask, headers);
        this.state = new AtomicReference<>(State.STARTED);
    }

    @Override
    public boolean shouldCancelChildrenOnCancellation() {
        return true;
    }

    // In case of persistent tasks we always need to return: `false`
    // because in case of persistent task the parent task isn't a task in the task manager, but in cluster state.
    // This instructs the task manager not to try to kill this persistent task when the task manager cannot find
    // a fake parent node id "cluster" in the cluster state
    @Override
    public final boolean cancelOnParentLeaving() {
        return false;
    }

    @Override
    public Status getStatus() {
        return new PersistentTasksNodeService.Status(state.get());
    }

    /**
     * Updates the persistent state for the corresponding persistent task.
     * <p>
     * This doesn't affect the status of this allocated task.
     */
    public void updatePersistentTaskState(final PersistentTaskState state,
                                          final ActionListener<PersistentTasksCustomMetadata.PersistentTask<?>> listener) {
        persistentTasksService.sendUpdateStateRequest(persistentTaskId, allocationId, state, listener);
    }

    public String getPersistentTaskId() {
        return persistentTaskId;
    }

    protected void init(PersistentTasksService persistentTasksService, TaskManager taskManager,
                        String persistentTaskId, long allocationId) {
        this.persistentTasksService = persistentTasksService;
        this.taskManager = taskManager;
        this.persistentTaskId = persistentTaskId;
        this.allocationId = allocationId;
    }

    public Exception getFailure() {
        return failure;
    }

    public long getAllocationId() {
        return allocationId;
    }

    /**
     * Waits for a given persistent task to comply with a given predicate, then call back the listener accordingly.
     *
     * @param predicate the persistent task predicate to evaluate
     * @param timeout a timeout for waiting
     * @param listener the callback listener
     */
    public void waitForPersistentTask(final Predicate<PersistentTasksCustomMetadata.PersistentTask<?>> predicate,
                                      final @Nullable TimeValue timeout,
                                      final PersistentTasksService.WaitForPersistentTaskListener<?> listener) {
        persistentTasksService.waitForPersistentTaskCondition(persistentTaskId, predicate, timeout, listener);
    }

    protected final boolean isCompleted() {
        return state.get() == State.COMPLETED;
    }

    boolean markAsCancelled() {
        return state.compareAndSet(State.STARTED, State.PENDING_CANCEL);
    }

    public void markAsCompleted() {
        completeAndNotifyIfNeeded(null);
    }

    public void markAsFailed(Exception e) {
        if (CancelTasksRequest.DEFAULT_REASON.equals(getReasonCancelled())) {
            completeAndNotifyIfNeeded(null);
        } else {
            completeAndNotifyIfNeeded(e);
        }
    }

    private void completeAndNotifyIfNeeded(@Nullable Exception failure) {
        final State prevState = state.getAndSet(State.COMPLETED);
        if (prevState == State.COMPLETED) {
            logger.warn("attempt to complete task [{}] with id [{}] in the [{}] state", getAction(), getPersistentTaskId(), prevState);
        } else {
            if (failure != null) {
                logger.warn(() -> new ParameterizedMessage("task {} failed with an exception", getPersistentTaskId()), failure);
            }
            try {
                this.failure = failure;
                if (prevState == State.STARTED) {
                    logger.trace("sending notification for completed task [{}] with id [{}]", getAction(), getPersistentTaskId());
                    persistentTasksService.sendCompletionRequest(getPersistentTaskId(), getAllocationId(), failure, new
                            ActionListener<PersistentTasksCustomMetadata.PersistentTask<?>>() {
                                @Override
                                public void onResponse(PersistentTasksCustomMetadata.PersistentTask<?> persistentTask) {
                                    logger.trace("notification for task [{}] with id [{}] was successful", getAction(),
                                            getPersistentTaskId());
                                }

                                @Override
                                public void onFailure(Exception e) {
                                    logger.warn(() -> new ParameterizedMessage(
                                        "notification for task [{}] with id [{}] failed", getAction(), getPersistentTaskId()), e);
                                }
                            });
                }
            } finally {
                taskManager.unregister(this);
            }
        }
    }

    public enum State {
        STARTED,  // the task is currently running
        PENDING_CANCEL, // the task is cancelled on master, cancelling it locally
        COMPLETED     // the task is done running and trying to notify caller
    }
}
