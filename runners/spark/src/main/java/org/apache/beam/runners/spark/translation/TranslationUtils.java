package org.apache.beam.runners.spark.translation;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.beam.runners.spark.aggregators.NamedAggregators;
import org.apache.beam.runners.spark.coders.CoderHelpers;
import org.apache.beam.runners.spark.util.BroadcastHelper;
import org.apache.beam.runners.spark.util.ByteArray;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.OldDoFn;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.GroupAlsoByWindowsViaOutputBufferDoFn;
import org.apache.beam.sdk.util.GroupByKeyViaGroupByKeyOnly;
import org.apache.beam.sdk.util.SystemReduceFn;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.util.state.InMemoryStateInternals;
import org.apache.beam.sdk.util.state.StateInternals;
import org.apache.beam.sdk.util.state.StateInternalsFactory;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.spark.Accumulator;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;

import scala.Tuple2;

/**
 * A set of utilities to help translating Beam transformations into Spark transformations.
 */
public final class TranslationUtils {

  private TranslationUtils() {
  }


  /**
   * In-memory state internals factory.
   *
   * @param <K> State key type.
   */
  public static class InMemoryStateInternalsFactory<K> implements StateInternalsFactory<K>,
      Serializable {
    @Override
    public StateInternals<K> stateInternalsForKey(K key) {
      return InMemoryStateInternals.forKey(key);
    }
  }

  /**
   * A {@link Combine.GroupedValues} function applied to grouped KVs.
   *
   * @param <K>       Grouped key type.
   * @param <InputT>  Grouped values type.
   * @param <OutputT> Output type.
   */
  public static class CombineGorupedValues<K, InputT, OutputT> implements
      Function<WindowedValue<KV<K, Iterable<InputT>>>, WindowedValue<KV<K, OutputT>>> {
    private final Combine.KeyedCombineFn<K, InputT, ?, OutputT> keyed;

    public CombineGorupedValues(Combine.GroupedValues<K, InputT, OutputT> transform) {
      //noinspection unchecked
      keyed = (Combine.KeyedCombineFn<K, InputT, ?, OutputT>) transform.getFn();
    }

    @Override
    public WindowedValue<KV<K, OutputT>> call(WindowedValue<KV<K,
            Iterable<InputT>>> windowedKv)
        throws Exception {
      KV<K, Iterable<InputT>> kv = windowedKv.getValue();
      return WindowedValue.of(KV.of(kv.getKey(), keyed.apply(kv.getKey(), kv.getValue())),
          windowedKv.getTimestamp(), windowedKv.getWindows(), windowedKv.getPane());
    }
  }

  /**
   * Checks if the window transformation should be applied or skipped.
   *
   * @param transform The {@link Window.Bound} transformation.
   * @param context   The {@link EvaluationContext}.
   * @param <T>       PCollection type.
   * @param <W>       {@link BoundedWindow} type.
   * @return if to apply the transformation.
   */
  public static <T, W extends BoundedWindow> boolean
  skipAssignWindows(Window.Bound<T> transform, EvaluationContext context) {
    @SuppressWarnings("unchecked")
    WindowFn<? super T, W> windowFn = (WindowFn<? super T, W>) transform.getWindowFn();
    // Avoid running assign windows if both source and destination are global window
    // or if the user has not specified the WindowFn (meaning they are just messing
    // with triggering or allowed lateness)
    return windowFn == null
        || (context.getInput(transform).getWindowingStrategy().getWindowFn()
            instanceof GlobalWindows
                && windowFn instanceof GlobalWindows);
  }

  /** Transform a pair stream into a value stream. */
  public static <T1, T2> JavaDStream<T2> dStreamValues(JavaPairDStream<T1, T2> pairDStream) {
    return pairDStream.map(new Function<Tuple2<T1, T2>, T2>() {
      @Override
      public T2 call(Tuple2<T1, T2> v1) throws Exception {
        return v1._2();
      }
    });
  }

  /** {@link KV} to pair function. */
  public static <K, V> PairFunction<KV<K, V>, K, V> toPairFunction() {
    return new PairFunction<KV<K, V>, K, V>() {
      @Override
      public Tuple2<K, V> call(KV<K, V> kv) {
        return new Tuple2<>(kv.getKey(), kv.getValue());
      }
    };
  }

  /**  A pair to {@link KV} function . */
  public static <K, V> Function<Tuple2<K, V>, KV<K, V>> fromPairFunction() {
    return new Function<Tuple2<K, V>, KV<K, V>>() {
      @Override
      public KV<K, V> call(Tuple2<K, V> t2) {
        return KV.of(t2._1(), t2._2());
      }
    };
  }

  /**
   * A utility class to filter {@link TupleTag}s.
   *
   * @param <V> TupleTag type.
   */
  public static final class TupleTagFilter<V>
      implements Function<Tuple2<TupleTag<V>, WindowedValue<?>>, Boolean> {

    private final TupleTag<V> tag;

    public TupleTagFilter(TupleTag<V> tag) {
      this.tag = tag;
    }

    @Override
    public Boolean call(Tuple2<TupleTag<V>, WindowedValue<?>> input) {
      return tag.equals(input._1());
    }
  }

  /***
   * Create SideInputs as Broadcast variables.
   *
   * @param views   The {@link PCollectionView}s.
   * @param context The {@link EvaluationContext}.
   * @return a map of tagged {@link BroadcastHelper}s.
   */
  public static Map<TupleTag<?>, BroadcastHelper<?>> getSideInputs(List<PCollectionView<?>> views,
      EvaluationContext context) {
    if (views == null) {
      return ImmutableMap.of();
    } else {
      Map<TupleTag<?>, BroadcastHelper<?>> sideInputs = Maps.newHashMap();
      for (PCollectionView<?> view : views) {
        Iterable<? extends WindowedValue<?>> collectionView = context.getPCollectionView(view);
        Coder<Iterable<WindowedValue<?>>> coderInternal = view.getCoderInternal();
        @SuppressWarnings("unchecked")
        BroadcastHelper<?> helper =
                BroadcastHelper.create((Iterable<WindowedValue<?>>) collectionView, coderInternal);
        //broadcast side inputs
        helper.broadcast(context.getSparkContext());
        sideInputs.put(view.getTagInternal(), helper);
      }
      return sideInputs;
    }
  }

  /***
   * Apply {@link GroupByKeyViaGroupByKeyOnly.GroupByKeyOnly} to a Spark RDD.
   */
  public static <K, V> JavaRDD<WindowedValue<KV<K, Iterable<V>>>> groupByKeyOnly(
      JavaRDD<WindowedValue<KV<K, V>>> rdd, Coder<K> keyCoder, Coder<V> valueCoder) {
    // Use coders to convert objects in the PCollection to byte arrays, so they
    // can be transferred over the network for the shuffle.
    return rdd.map(WindowingHelpers.<KV<K, V>>unwindowFunction())
        .mapToPair(TranslationUtils.<K, V>toPairFunction())
        .mapToPair(CoderHelpers.toByteFunction(keyCoder, valueCoder))
        .groupByKey()
        .mapToPair(CoderHelpers.fromByteFunctionIterable(keyCoder, valueCoder))
        // empty windows are OK here, see GroupByKey#evaluateHelper in the SDK
        .map(TranslationUtils.<K, Iterable<V>>fromPairFunction())
        .map(WindowingHelpers.<KV<K, Iterable<V>>>windowFunction());
  }

  /***
   * Apply {@link GroupByKeyViaGroupByKeyOnly.GroupAlsoByWindow} to a Spark RDD.
   */
  public static <K, V, W extends BoundedWindow>
      JavaRDD<WindowedValue<KV<K, Iterable<V>>>>
          groupAlsoByWindow(JavaRDD<WindowedValue<KV<K, Iterable<WindowedValue<V>>>>> rdd,
                            GroupByKeyViaGroupByKeyOnly.GroupAlsoByWindow<K, V> transform,
                            SparkRuntimeContext runtimeContext,
                            Accumulator<NamedAggregators> accum,
                            KvCoder<K, Iterable<WindowedValue<V>>> inputKvCoder) {
    Coder<Iterable<WindowedValue<V>>> inputValueCoder = inputKvCoder.getValueCoder();
    IterableCoder<WindowedValue<V>> inputIterableValueCoder =
        (IterableCoder<WindowedValue<V>>) inputValueCoder;

    Coder<WindowedValue<V>> inputIterableElementCoder = inputIterableValueCoder.getElemCoder();
    WindowedValue.WindowedValueCoder<V> inputIterableWindowedValueCoder =
        (WindowedValue.WindowedValueCoder<V>) inputIterableElementCoder;

    Coder<V> inputIterableElementValueCoder = inputIterableWindowedValueCoder.getValueCoder();

    @SuppressWarnings("unchecked")
    WindowingStrategy<?, W> windowingStrategy =
        (WindowingStrategy<?, W>) transform.getWindowingStrategy();

    // GroupAlsoByWindow current uses a dummy in-memory StateInternals
    OldDoFn<KV<K, Iterable<WindowedValue<V>>>, KV<K, Iterable<V>>> gabwDoFn =
        new GroupAlsoByWindowsViaOutputBufferDoFn<K, V, Iterable<V>, W>(
            windowingStrategy, new TranslationUtils.InMemoryStateInternalsFactory<K>(),
                SystemReduceFn.<K, V, W>buffering(inputIterableElementValueCoder));
    return rdd.mapPartitions(new DoFnFunction<>(accum, gabwDoFn, runtimeContext, null));
  }

  /**
   * Apply a composite {@link org.apache.beam.sdk.transforms.Combine.Globally} transformation.
   */
  public static <InputT, AccumT, OutputT> OutputT
  combineGlobally(JavaRDD<WindowedValue<InputT>> rdd,
                  final Combine.CombineFn<InputT, AccumT, OutputT> globally,
                  final Coder<InputT> iCoder,
                  final Coder<AccumT> aCoder) {
    // Use coders to convert objects in the PCollection to byte arrays, so they
    // can be transferred over the network for the shuffle.
    JavaRDD<byte[]> inRddBytes = rdd.map(WindowingHelpers.<InputT>unwindowFunction()).map(
        CoderHelpers.toByteFunction(iCoder));
    /*AccumT*/ byte[] acc = inRddBytes.aggregate(
        CoderHelpers.toByteArray(globally.createAccumulator(), aCoder),
        new Function2</*AccumT*/ byte[], /*InputT*/ byte[], /*AccumT*/ byte[]>() {
          @Override
          public /*AccumT*/ byte[] call(/*AccumT*/ byte[] ab, /*InputT*/ byte[] ib)
              throws Exception {
            AccumT a = CoderHelpers.fromByteArray(ab, aCoder);
            InputT i = CoderHelpers.fromByteArray(ib, iCoder);
            return CoderHelpers.toByteArray(globally.addInput(a, i), aCoder);
          }
        },
        new Function2</*AccumT*/ byte[], /*AccumT*/ byte[], /*AccumT*/ byte[]>() {
          @Override
          public /*AccumT*/ byte[] call(/*AccumT*/ byte[] a1b, /*AccumT*/ byte[] a2b)
              throws Exception {
            AccumT a1 = CoderHelpers.fromByteArray(a1b, aCoder);
            AccumT a2 = CoderHelpers.fromByteArray(a2b, aCoder);
            // don't use Guava's ImmutableList.of as values may be null
            List<AccumT> accumulators = Collections.unmodifiableList(Arrays.asList(a1, a2));
            AccumT merged = globally.mergeAccumulators(accumulators);
            return CoderHelpers.toByteArray(merged, aCoder);
          }
        }
    );
    return globally.extractOutput(CoderHelpers.fromByteArray(acc, aCoder));
  }

  /**
   * Apply a composite {@link org.apache.beam.sdk.transforms.Combine.PerKey} transformation.
   */
  public static <K, InputT, AccumT, OutputT> JavaRDD<WindowedValue<KV<K, OutputT>>>
  combinePerKey(JavaRDD<WindowedValue<KV<K, InputT>>> rdd,
                final Combine.KeyedCombineFn<K, InputT, AccumT, OutputT> keyed,
                final WindowedValue.FullWindowedValueCoder<K> wkCoder,
                final WindowedValue.FullWindowedValueCoder<KV<K, InputT>> wkviCoder,
                final WindowedValue.FullWindowedValueCoder<KV<K, AccumT>> wkvaCoder) {
    // We need to duplicate K as both the key of the JavaPairRDD as well as inside the value,
    // since the functions passed to combineByKey don't receive the associated key of each
    // value, and we need to map back into methods in Combine.KeyedCombineFn, which each
    // require the key in addition to the InputT's and AccumT's being merged/accumulated.
    // Once Spark provides a way to include keys in the arguments of combine/merge functions,
    // we won't need to duplicate the keys anymore.
    // Key has to bw windowed in order to group by window as well
    JavaPairRDD<WindowedValue<K>, WindowedValue<KV<K, InputT>>> inRddDuplicatedKeyPair =
        rdd.flatMapToPair(
            new PairFlatMapFunction<WindowedValue<KV<K, InputT>>, WindowedValue<K>,
                WindowedValue<KV<K, InputT>>>() {
              @Override
              public Iterable<Tuple2<WindowedValue<K>,
                  WindowedValue<KV<K, InputT>>>>
              call(WindowedValue<KV<K, InputT>> kv) {
                  List<Tuple2<WindowedValue<K>,
                      WindowedValue<KV<K, InputT>>>> tuple2s =
                      Lists.newArrayListWithCapacity(kv.getWindows().size());
                  for (BoundedWindow boundedWindow: kv.getWindows()) {
                    WindowedValue<K> wk = WindowedValue.of(kv.getValue().getKey(),
                        boundedWindow.maxTimestamp(), boundedWindow, kv.getPane());
                    tuple2s.add(new Tuple2<>(wk, kv));
                  }
                return tuple2s;
              }
            });
    // Use coders to convert objects in the PCollection to byte arrays, so they
    // can be transferred over the network for the shuffle.
    JavaPairRDD<ByteArray, byte[]> inRddDuplicatedKeyPairBytes = inRddDuplicatedKeyPair
        .mapToPair(CoderHelpers.toByteFunction(wkCoder, wkviCoder));

    // The output of combineByKey will be "AccumT" (accumulator)
    // types rather than "OutputT" (final output types) since Combine.CombineFn
    // only provides ways to merge VAs, and no way to merge VOs.
    JavaPairRDD</*K*/ ByteArray, /*KV<K, AccumT>*/ byte[]> accumulatedBytes =
        inRddDuplicatedKeyPairBytes.combineByKey(
        new Function</*KV<K, InputT>*/ byte[], /*KV<K, AccumT>*/ byte[]>() {
          @Override
          public /*KV<K, AccumT>*/ byte[] call(/*KV<K, InputT>*/ byte[] input) {
            WindowedValue<KV<K, InputT>> wkvi =
                CoderHelpers.fromByteArray(input, wkviCoder);
            AccumT va = keyed.createAccumulator(wkvi.getValue().getKey());
            va = keyed.addInput(wkvi.getValue().getKey(), va, wkvi.getValue().getValue());
            WindowedValue<KV<K, AccumT>> wkva =
                WindowedValue.of(KV.of(wkvi.getValue().getKey(), va), wkvi.getTimestamp(),
                wkvi.getWindows(), wkvi.getPane());
            return CoderHelpers.toByteArray(wkva, wkvaCoder);
          }
        },
        new Function2</*KV<K, AccumT>*/ byte[],
            /*KV<K, InputT>*/ byte[],
            /*KV<K, AccumT>*/ byte[]>() {
          @Override
          public /*KV<K, AccumT>*/ byte[] call(/*KV<K, AccumT>*/ byte[] acc,
              /*KV<K, InputT>*/ byte[] input) {
            WindowedValue<KV<K, AccumT>> wkva =
                CoderHelpers.fromByteArray(acc, wkvaCoder);
            WindowedValue<KV<K, InputT>> wkvi =
                CoderHelpers.fromByteArray(input, wkviCoder);
            AccumT va =
                keyed.addInput(wkva.getValue().getKey(), wkva.getValue().getValue(),
                wkvi.getValue().getValue());
            wkva = WindowedValue.of(KV.of(wkva.getValue().getKey(), va), wkva.getTimestamp(),
                wkva.getWindows(), wkva.getPane());
            return CoderHelpers.toByteArray(wkva, wkvaCoder);
          }
        },
        new Function2</*KV<K, AccumT>*/ byte[],
            /*KV<K, AccumT>*/ byte[],
            /*KV<K, AccumT>*/ byte[]>() {
          @Override
          public /*KV<K, AccumT>*/ byte[] call(/*KV<K, AccumT>*/ byte[] acc1,
              /*KV<K, AccumT>*/ byte[] acc2) {
            WindowedValue<KV<K, AccumT>> wkva1 =
                CoderHelpers.fromByteArray(acc1, wkvaCoder);
            WindowedValue<KV<K, AccumT>> wkva2 =
                CoderHelpers.fromByteArray(acc2, wkvaCoder);
            AccumT va = keyed.mergeAccumulators(wkva1.getValue().getKey(),
                // don't use Guava's ImmutableList.of as values may be null
                Collections.unmodifiableList(Arrays.asList(wkva1.getValue().getValue(),
                wkva2.getValue().getValue())));
            WindowedValue<KV<K, AccumT>> wkva =
                WindowedValue.of(KV.of(wkva1.getValue().getKey(),
                va), wkva1.getTimestamp(), wkva1.getWindows(), wkva1.getPane());
            return CoderHelpers.toByteArray(wkva, wkvaCoder);
          }
        });

    JavaPairRDD<WindowedValue<K>, WindowedValue<OutputT>> extracted = accumulatedBytes
        .mapToPair(CoderHelpers.fromByteFunction(wkCoder, wkvaCoder))
        .mapValues(
            new Function<WindowedValue<KV<K, AccumT>>, WindowedValue<OutputT>>() {
              @Override
              public WindowedValue<OutputT> call(WindowedValue<KV<K, AccumT>> acc) {
                return WindowedValue.of(keyed.extractOutput(acc.getValue().getKey(),
                    acc.getValue().getValue()), acc.getTimestamp(),
                    acc.getWindows(), acc.getPane());
              }
            });
    return extracted.map(TranslationUtils.<WindowedValue<K>,
        WindowedValue<OutputT>>fromPairFunction()).map(
            new Function<KV<WindowedValue<K>, WindowedValue<OutputT>>,
                WindowedValue<KV<K, OutputT>>>() {
              @Override
              public WindowedValue<KV<K, OutputT>> call(KV<WindowedValue<K>,
                  WindowedValue<OutputT>> kwvo) throws Exception {
                WindowedValue<OutputT> wvo = kwvo.getValue();
                KV<K, OutputT> kvo = KV.of(kwvo.getKey().getValue(), wvo.getValue());
                return WindowedValue.of(kvo, wvo.getTimestamp(), wvo.getWindows(), wvo.getPane());
              }
            });
  }
}
