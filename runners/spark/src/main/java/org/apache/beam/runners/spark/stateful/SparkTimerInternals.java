/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.spark.stateful;

import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.beam.runners.spark.util.GlobalWatermarkHolder.MicrobatchTime;
import org.apache.beam.sdk.util.TimeDomain;
import org.apache.beam.sdk.util.TimerInternals;
import org.apache.beam.sdk.util.state.StateNamespace;
import org.apache.spark.broadcast.Broadcast;
import org.joda.time.Instant;


/**
 * An implementation of {@link TimerInternals} for the SparkRunner.
 */
class SparkTimerInternals implements TimerInternals {
  private final Instant lowWatermark;
  private final Instant highWatermark;
  private final Instant synchronizedProcessingTime;
  private final Set<TimerData> timers = Sets.newHashSet();

  private Instant inputWatermark;

  SparkTimerInternals(@Nullable Broadcast<MicrobatchTime> broadcast) {
    lowWatermark = broadcast != null ? broadcast.getValue().getLowWatermark()
        : new Instant(0);
    highWatermark = broadcast != null ? broadcast.getValue().getHighWatermark()
        : new Instant(0);
    synchronizedProcessingTime = broadcast != null
        ? broadcast.getValue().getSynchronizedProcessingTime() : new Instant(0);
    inputWatermark = lowWatermark;
  }

  Collection<TimerData> getTimers() {
    return timers;
  }

  /** This should only be called after processing the element. */
  Collection<TimerData> getTimersReadyToProcess() {
    Set<TimerData> toFire = Sets.newHashSet();
    Iterator<TimerData> iterator = timers.iterator();
    while (iterator.hasNext()) {
      TimerData timer = iterator.next();
      if (timer.getTimestamp().isBefore(inputWatermark)) {
        toFire.add(timer);
        iterator.remove();
      }
    }
    return toFire;
  }

  void addTimers(Collection<TimerData> timers) {
    this.timers.addAll(timers);
  }

  @Override
  public void setTimer(TimerData timer) {
    this.timers.add(timer);
  }

  @Override
  public void deleteTimer(StateNamespace namespace, String timerId, TimeDomain timeDomain) {
    throw new UnsupportedOperationException("Deleting a timer by ID is not yet supported.");
  }

  @Override
  public void deleteTimer(TimerData timer) {
    this.timers.remove(timer);
  }

  @Override
  public Instant currentProcessingTime() {
    return Instant.now();
  }

  @Nullable
  @Override
  public Instant currentSynchronizedProcessingTime() {
    return synchronizedProcessingTime;
  }

  @Override
  public Instant currentInputWatermarkTime() {
    return inputWatermark;
  }

  /** Advances the watermark - since */
  public void advanceWatermark() {
    inputWatermark = highWatermark;
  }

  /**
   * Returns the "high" watermark, representing the microbatch global end-of-read watermark.
   */
  Instant currentHighWatermark() {
    return highWatermark;
  }

  @Nullable
  @Override
  public Instant currentOutputWatermarkTime() {
    return null;
  }

  @Override
  public void setTimer(
      StateNamespace namespace,
      String timerId,
      Instant target,
      TimeDomain timeDomain) {
    throw new UnsupportedOperationException("Setting a timer by ID not yet supported.");
  }

  @Override
  public void deleteTimer(StateNamespace namespace, String timerId) {
    throw new UnsupportedOperationException("Deleting a timer by ID is not yet supported.");
  }

}
