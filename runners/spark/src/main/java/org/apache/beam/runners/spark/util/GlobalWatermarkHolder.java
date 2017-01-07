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

package org.apache.beam.runners.spark.util;

import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.joda.time.Instant;

/**
 * A {@link Broadcast} variable to hold the global watermark.
 */
public class GlobalWatermarkHolder {

  private static volatile Broadcast<Instant> instance = null;
  private static final Instant MIN_WATERMARK = new Instant(Long.MIN_VALUE);

  public static Instant getValue() {
    if (instance == null) {
      return MIN_WATERMARK;
    }
    return instance.getValue();
  }

  public static Broadcast<Instant> get() {
    return instance;
  }

  public static void update(JavaSparkContext jsc, Instant watermark) {
    if (instance == null || instance.getValue().isBefore(watermark)) {
      synchronized (GlobalWatermarkHolder.class){
        if (instance == null || instance.getValue().isBefore(watermark)) {
          if (instance != null) {
            instance.unpersist(true);
          }
          instance = jsc.broadcast(watermark);
        }
      }
    }
  }
}
