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

package org.apache.beam.runners.spark.translation.streaming;

import org.apache.beam.runners.spark.SparkPipelineOptions;
import org.apache.beam.runners.spark.SparkRunner;
import org.apache.beam.runners.spark.translation.SparkContextFactory;
import org.apache.beam.runners.spark.translation.SparkPipelineTranslator;
import org.apache.beam.runners.spark.translation.TransformTranslator;
import org.apache.beam.sdk.Pipeline;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.api.java.JavaStreamingContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link JavaStreamingContext} factory for resilience.
 * @see <a href="https://spark.apache.org/docs/1.6.2/streaming-programming-guide.html#how-to-configure-checkpointing">how-to-configure-checkpointing</a>
 */
public class SparkRunnerStreamingContextFactory implements JavaStreamingContextFactory {
  private static final Logger LOG =
      LoggerFactory.getLogger(SparkRunnerStreamingContextFactory.class);

  private final Pipeline pipeline;
  private final SparkPipelineOptions options;

  public SparkRunnerStreamingContextFactory(Pipeline pipeline, SparkPipelineOptions options) {
    this.pipeline = pipeline;
    this.options = options;
  }

  private StreamingEvaluationContext ctxt;

  @Override
  public JavaStreamingContext create() {
    LOG.info("Creating a new Spark Streaming Context");

    SparkPipelineTranslator translator = new StreamingTransformTranslator.Translator(
        new TransformTranslator.Translator());
    Duration batchDuration = new Duration(options.getBatchIntervalMillis());
    LOG.info("Setting Spark streaming batchDuration to {} msec", batchDuration.milliseconds());

    JavaSparkContext jsc = SparkContextFactory.getSparkContext(options);
    JavaStreamingContext jssc = new JavaStreamingContext(jsc, batchDuration);
    ctxt = new StreamingEvaluationContext(jsc, pipeline, jssc,
        options.getTimeout());
    pipeline.traverseTopologically(new SparkRunner.Evaluator(translator, ctxt));
    ctxt.computeOutputs();

    jssc.checkpoint(options.getCheckpointDir());
    return jssc;
  }

  public StreamingEvaluationContext getCtxt() {
    return ctxt;
  }
}