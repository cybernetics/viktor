package org.jetbrains.bio.viktor

import org.apache.commons.math3.util.Precision
import java.text.DecimalFormat
import java.util.*
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A strided n-dimensional array stored in a [DoubleArray].
 *
 * The term *strided* means that unlike regular [DoubleArray] the
 * elements of an [F64Array] can be at arbitrary index intervals
 * (strides) from each other. For example
 *
 * ```
 * data = [0, 1, 2, 3, 4, 5]
 * offset = 1
 * shape = [2]
 * strides = [3]
 * ```
 *
 * corresponds to a 1-D array with elements
 *
 * ```
 * [1, 4]
 * ```
 *
 * Due to instantiation contracts, the actual instance of this exact class will always be non-flat, i.e.
 * have at least two dimensions. One-dimensional array will be [F64FlatArray] (or possibly a descendant of that),
 * and zero-dimensional (singleton) arrays are not allowed.
 *
 * Method tags:
 * - "in-place": this operation will modify the array
 * - "copying": this operation creates a copy fully independent from the original array
 * - "viewer": this operation creates a new array which shares data with the original one;
 * modifications to one will be seen through the other, and no copying is performed
 *
 * @author Sergei Lebedev
 * @since 0.4.0
 */
open class F64Array protected constructor(
        /** Raw data array. */
        val data: DoubleArray,
        /** Offset of the first vector element in the raw data array. */
        val offset: Int,
        /** Indexing steps along each axis. */
        val strides: IntArray,
        /** Number of elements along each axis. */
        val shape: IntArray
) {

    /** Number of axes in this array. */
    val nDim: Int get() = shape.size

    /** Number of elements along the first axis. */
    val size: Int get() = shape.first()

    /**
     * The maximum number of dimensions suitable for unrolling, see [unrollOnce].
     */
    private val unrollDim: Int

    /**
     * The size of the maximum unrolled subarray sequence, see [unrollOnce].
     */
    private val unrollSize: Int

    /**
     * The stride of the maximum unrolled subarray sequence, see [unrollOnce].
     */
    private val unrollStride: Int

    /**
     * Returns `true` if this array can be flattened using [flatten].
     *
     * Flattenable array's elements are laid out with a constant stride. This allows to use simple loops when iterating.
     * Calling [flatten] on a non-flattenable array will produce an [IllegalStateException].
     *
     * A particular case of a flattenable array is a dense array whose elements occupy a contiguous block of memory.
     * Large dense arrays employ native SIMD optimizations, see [F64LargeDenseArray].
     */
    open val isFlattenable get() = (unrollDim == nDim)

    init {
        require(strides.size == shape.size) {
            "Strides and shape have different sizes (${strides.size} and ${shape.size})"
        }
        // calculation of unrollDim, unrollStride and unrollSize
        var prevStride = 0
        var unrollable = true
        var d = 0
        var s = 0
        for (i in strides.indices) {
            if (shape[i] == 1) {
                if (unrollable) d = i + 1
                continue
            }
            if (unrollable && (prevStride == 0 || prevStride == strides[i] * shape[i])) {
                d = i + 1
                s = strides[i]
            } else {
                unrollable = false
            }
            prevStride = strides[i]
        }
        unrollDim = d
        unrollStride = s
        unrollSize = shape.slice(0 until d).toIntArray().product()
    }

    /**
     * Generic getter.
     *
     * Note that it could be at least 1.5x slower than specialized versions for 1, 2 or 3 dimensions.
     */
    operator fun get(vararg indices: Int): Double {
        check(indices.size == nDim) { "broadcasting get is not supported" }
        return safeIndex({ indices }) { data[unsafeIndex(indices)] }
    }

    operator fun get(pos: Int): Double {
        check(nDim == 1) { "broadcasting get is not supported" }
        return safeIndex({ intArrayOf(pos) }) { data[unsafeIndex(pos)] }
    }

    operator fun get(r: Int, c: Int): Double {
        check(nDim == 2) { "broadcasting get is not supported" }
        return safeIndex({ intArrayOf(r, c) }) { data[unsafeIndex(r, c)] }
    }

    operator fun get(d: Int, r: Int, c: Int): Double {
        check(nDim == 3) { "broadcasting get is not supported" }
        return safeIndex({ intArrayOf(d, r, c) }) { data[unsafeIndex(d, r, c)] }
    }

    /**
     * Generic setter.
     *
     * Note that it could be at least 1.5x slower than specialized versions for 1, 2 or 3 dimensions.
     */
    operator fun set(vararg indices: Int, value: Double) {
        check(indices.size == nDim) { "broadcasting set is not supported" }
        safeIndex({ indices }) { data[unsafeIndex(indices)] = value }
    }

    operator fun set(pos: Int, value: Double) {
        check(nDim == 1) { "broadcasting set is not supported" }
        safeIndex({ intArrayOf(pos) }) { data[unsafeIndex(pos)] = value }
    }

    operator fun set(r: Int, c: Int, value: Double) {
        check(nDim == 2) { "broadcasting set is not supported" }
        safeIndex({ intArrayOf(r, c) }) { data[unsafeIndex(r, c)] = value }
    }

    operator fun set(d: Int, r: Int, c: Int, value: Double) {
        check(nDim == 3) { "broadcasting set is not supported" }
        safeIndex({ intArrayOf(d, r, c) }) { data[unsafeIndex(d, r, c)] = value }
    }

    @Suppress("nothing_to_inline")
    internal inline fun unsafeGet(pos: Int): Double = data[unsafeIndex(pos)]

    @Suppress("nothing_to_inline")
    internal inline fun unsafeSet(pos: Int, value: Double) {
        data[unsafeIndex(pos)] = value
    }

    @Suppress("nothing_to_inline")
    private inline fun unsafeIndex(pos: Int): Int {
        return offset + pos * strides[0]
    }

    @Suppress("nothing_to_inline")
    private inline fun unsafeIndex(r: Int, c: Int): Int {
        return offset + r * strides[0] + c * strides[1]
    }

    @Suppress("nothing_to_inline")
    private inline fun unsafeIndex(d: Int, r: Int, c: Int): Int {
        return offset + d * strides[0] + r * strides[1] + c * strides[2]
    }

    @Suppress("nothing_to_inline")
    private inline fun unsafeIndex(indices: IntArray): Int {
        return strides.foldIndexed(offset) { i, acc, stride -> acc + indices[i] * stride }
    }

    private inline fun <T> safeIndex(indices: () -> IntArray, block: () -> T): T {
        try {
            return block()
        } catch (e: IndexOutOfBoundsException) {
            outOfBounds(indices(), shape)
        }
    }

    @Suppress("nothing_to_inline")
    private inline fun outOfBounds(indices: IntArray, shape: IntArray): Nothing {
        val nDim = shape.size
        val reason = when {
            indices.size > nDim -> "too many indices"
            indices.size < nDim -> "too few indices"
            else -> "(${indices.joinToString(", ")}) out of bounds " +
                    "for shape ${shape.joinToString(", ")}"
        }

        throw IndexOutOfBoundsException(reason)
    }

    /**
     * Returns a sequence of views along the specified [axis].
     *
     * For example, for a 2D array `axis = 0` means "for each row",
     * and `axis = 1` "for each column".
     *
     * Viewer method.
     */
    open fun along(axis: Int): Sequence<F64Array> = (0 until shape[axis]).asSequence().map { view(it, axis) }

    /**
     * Returns a view of this array along the specified [axis].
     *
     * Viewer method.
     */
    fun view(index: Int, axis: Int = 0): F64Array {
        checkIndex("axis", axis, nDim)
        checkIndex("index", index, shape[axis])
        return invoke(
            data, offset + strides[axis] * index,
            strides.remove(axis), shape.remove(axis)
        )
    }

    /**
     * Computes a nested view over the first axis.
     *
     * Here be dragons!
     */
    @Suppress("nothing_to_inline")
    private inline fun view0(indices: IntArray): F64Array {
        require(indices.size < nDim) { "too many indices" }
        return indices.fold(this) { m, pos -> m.view(pos) }
    }

    /**
     * Unrolls the array down to flattenable subarrays and flattens them.
     *
     * See [unrollOnce] for description of unrolling. This method performs recursive unrolling until it arrives
     * at flattenable subarrays, at which point it flattens them and returns the resulting sequence.
     * In particular, if the original array [isFlattenable], it's flattened and returned as a singleton sequence.
     */
    private fun unrollToFlat(): Sequence<F64FlatArray> {
        if (isFlattenable) return sequenceOf(flatten())
        return unrollOnce().flatMap { it.unrollToFlat() }
    }

    /**
     * Unrolls two arrays down to flattenable subarrays in a compatible way and applies [action] to the flattened pairs.
     *
     * Since two arrays with the same [shape] can have different internal organization (i.e. [strides]),
     * it's important to unroll them in a consistent way. This method provides the necessary functionality.
     *
     * @param action Since this method is always used in a for-each context, we include the corresponding lambda
     * in the signature.
     */
    private fun commonUnrollToFlat(
            other: F64Array,
            action: (F64FlatArray, F64FlatArray) -> Unit
    ) {
        checkShape(other)
        val commonUnrollDim = min(unrollDim, other.unrollDim)
        if (commonUnrollDim == nDim) {
            action(flatten(), other.flatten())
        } else {
            unrollOnce(commonUnrollDim).zip(other.unrollOnce(commonUnrollDim)).forEach { (a, b) ->
                a.commonUnrollToFlat(b, action)
            }
        }
    }

    /**
     * Unroll the array into a sequence of smaller subarrays in a reasonably efficient way.
     *
     * We can always iterate any array by performing nested loops over all its axes. However, in the vast majority
     * of cases this is unnecessarily complicated and at least some of the nested loops can be replaced with
     * a single loop. This is called "unrolling".
     *
     * [unrollDim] describes the maximum number of dimensions suitable for unrolling. When it's equal to [nDim],
     * the array [isFlattenable], meaning that all its elements are equidistant and can be visited in a single loop.
     *
     * This method assumes that the caller knows what they do and unrolls over specified dimensions without
     * checking e.g. whether the array [isFlattenable] and thus doesn't need any unrolling.
     */
    private fun unrollOnce(n: Int = unrollDim): Sequence<F64Array> {
        require(n <= unrollDim) { "can't unroll $n dimensions, only $unrollDim are unrollable" }
        val newStrides = strides.slice(n until nDim).toIntArray()
        val newShape = shape.slice(n until nDim).toIntArray()
        val currentUnrollStride = if (n == unrollDim) unrollStride else run {
            var nonTrivialN = n - 1
            while (nonTrivialN >= 0 && shape[nonTrivialN] <= 1) nonTrivialN--
            if (nonTrivialN >= 0) strides[nonTrivialN] else 0
        }
        val currentUnrollSize = if (n == unrollDim) unrollSize else shape.slice(0 until n).toIntArray().product()
        return (0 until currentUnrollSize).asSequence().map { i ->
            invoke(data, offset + currentUnrollStride * i, newStrides, newShape)
        }
    }

    /**
     * A broadcasted viewer for this array.
     *
     * The main difference between
     *     a[...]
     * and
     *     a.V[...]
     * is that the array's getter/setter methods deal with scalar [Double] values, while the viewer's methods
     * deal with [F64Array]s. Another difference is that the viewer's methods can skip dimensions by providing
     * [_I] object instead of an index.
     *
     * Consider a matrix (2D array) `a`. Then the following invocations have the following effect:
     *     a[4] // fails, since it doesn't reference a scalar
     *     a[4, 2] // returns a Double
     *     a.V[4] // returns 4th row
     *     a.V[_I, 2] // returns 2nd column
     *     a.V[4, 2] // fails, since it doesn't reference an array
     */
    @Suppress("PropertyName")
    @delegate:Transient
    val V: Viewer by lazy(LazyThreadSafetyMode.PUBLICATION) { Viewer(this) }

    class Viewer(private val a: F64Array) {
        /**
         * Returns a subarray with several first indices specified.
         *
         * A less-verbose alias to [view] and chained [view] calls:
         *     a.V[i0, i1, i2] == a.view(i0).view(i1).view(i2)
         *
         * For an appropriately sized `moreIndices` [IntArray], the following invariant holds:
         *     a[indices + moreIndices] == a.V[indices][moreIndices]
         *
         * It should be noted that the former invocation is generally much more efficient.
         *
         * Viewer method.
         */
        operator fun get(vararg indices: Int) = a.view0(indices)

        /**
         * Replaces a subarray with several first indices specified with the values from [other].
         *
         * After we call:
         *     a.V[indices] = other
         * the following holds for an appropriately sized `moreIndices` [IntArray]:
         *     a[indices + moreIndices] == other[moreIndices]
         *
         * In-place method for this and copying method for [other].
         */
        operator fun set(vararg indices: Int, other: F64Array) {
            other.copyTo(a.view0(indices))
        }

        /**
         * Fills a subarray with several first indices specified with [init] value.
         *
         * After we call:
         *     a.V[indices] = init
         * the following holds for any appropriately sized `moreIndices` [IntArray]:
         *     a[indices + moreIndices] == init
         *
         * In-place method.
         */
        operator fun set(vararg indices: Int, init: Double) {
            a.view0(indices).fill(init)
        }

        /**
         * Replaces the whole array with the values from [other].
         *
         * A less-verbose alias to [copyTo].
         *
         * In-place method for this and copying method for [other].
         */
        @Suppress("unused_parameter")
        operator fun set(any: _I, other: F64Array) {
            other.copyTo(a)
        }

        /**
         * Replaces the whole array with [other] value.
         *
         * A less-verbose alias to [fill].
         *
         * In-place method.
         */
        @Suppress("unused_parameter")
        operator fun set(any: _I, other: Double) {
            a.fill(other)
        }

        /**
         * Returns a subarray with index 1 specified to [c].
         *
         * A less-verbose alias to [view]:
         *     a.V[_I, c] == a.view(c, 1)
         *
         * Viewer method.
         */
        // XXX we could generalize this in a way similar to the above method.
        //     However, in that case the resulting methods could only be called via
        //     method call syntax with explicit parameter names. E.g.
        //
        //         get(any: _I, vararg rest: _I, c: Int, other: F64Array)
        //
        //     should be called as get(_I, _I, c = 42) and not [_I, _I, 42].
        @Suppress("unused_parameter")
        operator fun get(any: _I, c: Int) = a.view(c, axis = 1)

        /**
         * Replaces a subarray with index 1 specified to [c] with the values from [other].
         *
         * A less-verbose alias to [view]:
         *     a.V[_I, c] == a.view(c, 1)
         *
         * In-place method for this and copying method for [other].
         */
        @Suppress("unused_parameter")
        operator fun set(any: _I, c: Int, other: F64Array) {
            other.copyTo(a.view(c, axis = 1))
        }

        /**
         * Replaces a subarray with index 1 specified to [c] with the [init] value.
         *
         * In-place method.
         */
        @Suppress("unused_parameter")
        operator fun set(any: _I, c: Int, init: Double) {
            a.view(c, axis = 1).fill(init)
        }
    }

    /**
     * Returns a copy of this array.
     *
     * The copy has the same [shape] as the original, but not necessary the same [strides], since
     * the copy is always flattenable and dense, even if the original array is not.
     *
     * Copying method.
     */
    fun copy(): F64Array {
        val copy = invoke(*shape)
        copyTo(copy)
        return copy
    }

    /**
     * Copies elements in this array to [other] array.
     *
     * In-place method for [other] and copying method for this.
     */
    open fun copyTo(other: F64Array): Unit = commonUnrollToFlat(other) { a, b -> a.copyTo(b) }

    /**
     * Reshapes this array.
     *
     * The original and the new array contain the same elements in the same orider, if both are enumerated row-major.
     *
     * For example,
     *     F64Array.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).reshape(2, 3)
     * produces a 2x3 matrix:
     *     [
     *         [1.0, 2.0, 3.0],
     *         [4.0, 5.0, 6.0]
     *     ]
     *
     * Only supported for flattenable arrays.
     *
     * Viewer method.
     */
    open fun reshape(vararg shape: Int): F64Array = flatten().reshape(*shape)

    /**
     * Appends this array to another array.
     *
     * @param axis the axis along which the arrays are appended.
     * @since 0.2.3
     */
    fun append(other: F64Array, axis: Int = 0): F64Array {
        return concatenate(this, other, axis = axis)
    }

    /**
     * Flattens the array into a 1D view in O(1) time.
     *
     * Only implemented for flattenable arrays.
     *
     * Viewer method.
     */
    open fun flatten(): F64FlatArray {
        check(isFlattenable) { "array can't be flattened" }
        return F64FlatArray.invoke(data, offset, unrollStride, unrollSize)
    }

    /**
     * Creates a sliced view of this array in O(1) time.
     *
     * @param from the first index of the slice (inclusive).
     * @param to the last index of the slice (exclusive). `-1` is treated as "until the end", otherwise [to] must be
     * strictly greater than [from] (empty arrays are not allowed).
     * @param step indexing step.
     * @param axis to slice along.
     */
    fun slice(from: Int = 0, to: Int = -1, step: Int = 1, axis: Int = 0): F64Array {
        require(step > 0) { "slicing step must be positive, but was $step" }
        require(axis in 0 until nDim) { "axis out of bounds: $axis" }
        require(from >= 0) { "slicing start index must be positive, but was $from" }
        val actualTo = if (to != -1) {
            require(to > from) { "slicing end index $to must be greater than start index $from" }
            check(to <= shape[axis]) { "slicing end index out of bounds: $to > ${shape[axis]}" }
            to
        } else {
            check(shape[axis] > from) { "slicing start index out of bounds: $from >= ${shape[axis]}" }
            shape[axis]
        }

        val sliceStrides = strides.clone().apply { this[axis] *= step }
        val sliceShape = shape.clone().apply {
            this[axis] = (actualTo - from + step - 1) / step
        }
        return invoke(data, offset + from * strides[axis], sliceStrides, sliceShape)
    }

    open operator fun contains(other: Double): Boolean = unrollToFlat().any { it.contains(other) }

    /**
     * Fills this array with a given [init] value.
     *
     * In-place method.
     */
    open fun fill(init: Double): Unit = flatten().fill(init)

    /**
     * Applies a given permutation of indices to the elements in the array.
     *
     * In-place method.
     */
    open fun reorder(indices: IntArray, axis: Int = 0) {
        reorderInternal(
            this, indices, axis,
            get = { pos -> view(pos, axis).copy() },
            set = { pos, value -> value.copyTo(view(pos, axis)) }
        )
    }

    /**
     * Computes a dot product between two vectors.
     *
     * Only implemented for flat arrays.
     */
    open infix fun dot(other: ShortArray): Double = unsupported()

    /**
     * Computes a dot product between two vectors.
     *
     * Only implemented for flat arrays.
     */
    open infix fun dot(other: IntArray): Double = unsupported()

    /**
     * Computes a dot product between two vectors.
     *
     * Only implemented for flat arrays. Optimized for dense arrays.
     */
    infix fun dot(other: DoubleArray): Double = dot(other.asF64Array())

    /**
     * Computes a dot product between two vectors.
     *
     * Only implemented for flat arrays. Optimized for dense arrays.
     */
    open infix fun dot(other: F64Array): Double = unsupported()

    /**
     * Computes the mean of the elements.
     *
     * Optimized for dense arrays.
     */
    fun mean() = sum() / shape.product()

    /**
     * Computes the unbiased standard deviation of the elements.
     *
     * Optimized for dense arrays.
     *
     * @since 0.3.0
     */
    open fun sd(): Double {
        val s = sum()
        val s2 = dot(this)
        return sqrt((s2 - s * s / size) / (size - 1))
    }

    /**
     * Returns the sum of the elements.
     *
     * Optimized for dense arrays.
     */
    open fun sum(): Double {
        val acc = KahanSum()
        unrollToFlat().forEach { acc += it.sum() }
        return acc.result()
    }

    /**
     * Computes cumulative sum of the elements.
     *
     * In-place method. Only implemented for flat arrays.
     */
    open fun cumSum(): Unit = unsupported()

    /**
     * Returns the maximum element.
     *
     * If any of array elements is NaN, the result is undefined but will be one of the array elements.
     *
     * Optimized for dense arrays.
     */
    open fun max(): Double = unrollToFlat().map { it.max() }.max() ?: Double.NEGATIVE_INFINITY

    /**
     * Returns the index of the maximum element.
     *
     * If any of array elements is NaN, the result is undefined but will be a valid index.
     *
     * Only implemented for flat arrays.
     */
    open fun argMax(): Int = unsupported()

    /**
     * Returns the minimum element.
     *
     * If any of array elements is NaN, the result is undefined but will be one of the array elements.
     *
     * Optimized for dense arrays.
     */
    open fun min(): Double = unrollToFlat().map { it.min() }.min() ?: Double.POSITIVE_INFINITY

    /**
     * Returns the index of the minimum element.
     *
     * If any of array elements is NaN, the result is undefined but will be a valid index.
     *
     * Only implemented for flat arrays.
     */
    open fun argMin(): Int = unsupported()

    /**
     * Replaces each element x of this array with its exponent exp(x).
     *
     * In-place method. Optimized for dense arrays.
     */
    open fun expInPlace(): Unit = unrollToFlat().forEach { it.expInPlace() }

    /**
     * A copying version of [expInPlace].
     *
     * Copying method. Optimized for dense arrays.
     */
    fun exp() = copy().apply { expInPlace() }

    /**
     * Replaces each element x of this array with exp(x) - 1.
     *
     * In-place method. Optimized for dense arrays.
     *
     * @since 0.3.0
     */
    open fun expm1InPlace(): Unit = unrollToFlat().forEach { it.expm1InPlace() }

    /**
     * A copying version of [expm1InPlace].
     *
     * Copying method. Optimized for dense arrays.
     */
    fun expm1() = copy().apply { expm1InPlace() }

    /**
     * Replaces each element x of this array with its natural logarithm log(x).
     *
     * In-place method. Optimized for dense arrays.
     */
    open fun logInPlace(): Unit = unrollToFlat().forEach { it.logInPlace() }

    /**
     * A copying version of [logInPlace].
     *
     * Copying method. Optimized for dense arrays.
     */
    fun log() = copy().apply { logInPlace() }

    /**
     * Replaces each element x of this array with log(1 + x).
     *
     * In-place method. Optimized for dense arrays.
     *
     * @since 0.3.0
     */
    open fun log1pInPlace(): Unit = unrollToFlat().forEach { it.log1pInPlace() }

    /**
     * A copying version of [log1pInPlace].
     *
     * Copying method. Optimized for dense arrays.
     */
    fun log1p() = copy().apply { log1pInPlace() }

    /**
     * Rescales the elements so that the sum is 1.0.
     *
     * In-place method.
     */
    fun rescale() {
        this /= sum() + Precision.EPSILON * shape.product().toDouble()
    }

    /**
     * Rescales the elements so that the sum of their exponents is 1.0.
     *
     * In-place method.
     */
    fun logRescale() {
        this -= logSumExp()
    }

    /**
     * Computes
     *
     *     log(Σ_x exp(x))
     *
     * in a numerically stable way.
     */
    open fun logSumExp(): Double = unrollToFlat().map { it.logSumExp() }.logSumExp()

    /**
     * Plus-assign for values stored as logarithms.
     *
     * In other words, the same as invoking
     *
     *     this[*i] = log(exp(this[*i]) + exp(other[*i]))
     *
     * for every valid i.
     *
     * In-place method.
     */
    open fun logAddExpAssign(other: F64Array): Unit = commonUnrollToFlat(other) { a, b -> a.logAddExpAssign(b) }

    /**
     * Computes elementwise
     *
     *     log(exp(this[*i]) + exp(other[*i]))
     *
     * in a numerically stable way.
     *
     * Copying method.
     */
    infix fun logAddExp(other: F64Array): F64Array = copy().apply { logAddExpAssign(other) }

    operator fun unaryPlus() = this

    open operator fun unaryMinus(): F64Array = copy().apply { (-flatten()).reshape(*shape) }

    operator fun plus(other: F64Array) = copy().apply { this += other }

    open operator fun plusAssign(other: F64Array): Unit = commonUnrollToFlat(other) { a, b -> a += b }

    operator fun plus(update: Double) = copy().apply { this += update }

    open operator fun plusAssign(update: Double): Unit = unrollToFlat().forEach { it += update }

    operator fun minus(other: F64Array) = copy().apply { this -= other }

    open operator fun minusAssign(other: F64Array): Unit = commonUnrollToFlat(other) { a, b -> a -= b }

    operator fun minus(update: Double) = copy().apply { this -= update }

    open operator fun minusAssign(update: Double): Unit = unrollToFlat().forEach { it -= update }

    operator fun times(other: F64Array) = copy().apply { this *= other }

    open operator fun timesAssign(other: F64Array): Unit = commonUnrollToFlat(other) { a, b -> a *= b }

    operator fun times(update: Double) = copy().apply { this *= update }

    open operator fun timesAssign(update: Double): Unit = unrollToFlat().forEach { it *= update }

    operator fun div(other: F64Array) = copy().apply { this /= other }

    open operator fun divAssign(other: F64Array): Unit = commonUnrollToFlat(other) { a, b -> a /= b }

    operator fun div(update: Double) = copy().apply { this /= update }

    open operator fun divAssign(update: Double): Unit = unrollToFlat().forEach { it /= update }

    /** Ensures a given array has the same dimensions as this array. */
    protected fun checkShape(other: F64Array): F64Array {
        // Could relax this to "broadcastable".
        check(this === other || shape.contentEquals(other.shape)) {
            "operands shapes do not match: ${shape.contentToString()} vs ${other.shape.contentToString()}"
        }
        return other
    }

    /**
     * Returns a sequence of all array elements. Useful for testing.
     */
    internal open fun asSequence(): Sequence<Double> = unrollToFlat().flatMap { it.asSequence() }

    /**
     * Converts this array to a conventional Kotlin structure.
     *
     * For example, a vector will be converted to a [DoubleArray], a matrix will become `Array<DoubleArray>` etc.
     *
     * Copying method.
     */
    open fun toArray(): Any = toGenericArray()

    /**
     * Converts this array to an [Array].
     *
     * For example, a matrix will become `Array<DoubleArray>` etc.
     *
     * Copying method. Not implemented for flat arrays.
     */
    open fun toGenericArray(): Array<*> = Array(size) { view(it).toArray() }

    /**
     * Converts this vector to a [DoubleArray].
     *
     * Copying method. Only implemented for flat arrays.
     */
    open fun toDoubleArray(): DoubleArray = throw UnsupportedOperationException()

    /**
     * Creates a [String] representation of the given array.
     *
     * At most [maxDisplay] elements are printed for each dimension.
     */
    open fun toString(
            maxDisplay: Int,
            format: DecimalFormat = DecimalFormat("#.####")
    ): String {
        val sb = StringBuilder()
        sb.append('[')
        if (maxDisplay < size) {
            for (r in 0 until maxDisplay / 2) {
                sb.append(V[r].toString(maxDisplay, format)).append(", ")
            }

            sb.append("..., ")

            val leftover = maxDisplay - maxDisplay / 2
            for (r in size - leftover until size) {
                sb.append(V[r].toString(maxDisplay, format))
                if (r < size - 1) {
                    sb.append(", ")
                }
            }
        } else {
            for (r in 0 until size) {
                sb.append(V[r].toString(maxDisplay, format))
                if (r < size - 1) {
                    sb.append(", ")
                }
            }
        }

        sb.append(']')
        return sb.toString()
    }

    override fun toString() = toString(8)

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is F64Array -> false
        !shape.contentEquals(other.shape) -> false
        else -> (0 until size).all { view(it) == other.view(it) }
    }

    override fun hashCode(): Int = (0 until size).fold(1) { acc, r ->
        31 * acc + view(r).hashCode()
    }

    companion object {
        /**
         * Creates a zero-filled flat array of a given [shape].
         */
        operator fun invoke(vararg shape: Int): F64Array {
            return F64FlatArray(DoubleArray(shape.product())).reshape(*shape)
        }

        /**
         * Creates a flat array of a given [size] and fills it using [block].
         */
        inline operator fun invoke(size: Int, block: (Int) -> Double): F64Array {
            return invoke(size).apply {
                for (i in 0 until size) {
                    this[i] = block(i)
                }
            }
        }

        /**
         * Creates a matrix with a given number of rows and columns and fills it using [block].
         */
        inline operator fun invoke(
                numRows: Int,
                numColumns: Int,
                block: (Int, Int) -> Double
        ): F64Array {
            return invoke(numRows, numColumns).apply {
                for (r in 0 until numRows) {
                    for (c in 0 until numColumns) {
                        this[r, c] = block(r, c)
                    }
                }
            }
        }

        /**
         * Creates a 3D array with given dimensions and fills it using [block].
         */
        inline operator fun invoke(
                depth: Int,
                numRows: Int,
                numColumns: Int,
                block: (Int, Int, Int) -> Double
        ): F64Array {
            return invoke(depth, numRows, numColumns).apply {
                for (d in 0 until depth) {
                    for (r in 0 until numRows) {
                        for (c in 0 until numColumns) {
                            this[d, r, c] = block(d, r, c)
                        }
                    }
                }
            }
        }

        /**
         * Creates a vector from given elements.
         */
        fun of(first: Double, vararg rest: Double): F64Array {
            val data = DoubleArray(rest.size + 1)
            data[0] = first
            System.arraycopy(rest, 0, data, 1, rest.size)
            return data.asF64Array()
        }

        /**
         * Creates a vector filled with a given [init] element.
         */
        fun full(size: Int, init: Double) = invoke(size).apply { fill(init) }

        /**
         * Creates an array filled with a given [init] element.
         *
         * Note that [init] must be a named argument.
         */
        fun full(vararg shape: Int, init: Double): F64Array {
            return invoke(*shape).apply { fill(init) }
        }

        /**
         * Joins a sequence of arrays into a single array.
         *
         * @since 0.2.3
         */
        fun concatenate(first: F64Array, vararg rest: F64Array, axis: Int = 0): F64Array {
            for (other in rest) {
                require(other.shape.remove(axis).contentEquals(first.shape.remove(axis))) {
                    "input array shapes must be exactly equal for all dimensions except $axis"
                }
            }

            val shape = first.shape.clone().apply {
                this[axis] = first.shape[axis] + rest.sumBy { it.shape[axis] }
            }

            val result = invoke(*shape)
            var offset = 0
            for (a in arrayOf(first, *rest)) {
                if (a.size > 0) {
                    a.copyTo(result.slice(offset, offset + a.shape[axis], axis = axis))
                    offset += a.shape[axis]
                }
            }

            return result
        }

        /** "Smart" constructor. */
        internal operator fun invoke(
                data: DoubleArray,
                offset: Int,
                strides: IntArray,
                shape: IntArray
        ): F64Array {
            require(strides.isNotEmpty()) { "singleton arrays are not supported" }
            require(shape.isNotEmpty()) { "singleton arrays are not supported" }
            require(strides.size == shape.size) { "strides and shape size don't match" }
            return if (shape.size == 1) {
                F64FlatArray(data, offset, strides.single(), shape.single())
            } else {
                F64Array(data, offset, strides, shape)
            }
        }
    }
}

/**
 * Wraps a given array.
 *
 * Viewer method.
 */
fun DoubleArray.asF64Array(): F64Array {
    return F64FlatArray(this, 0, 1, size)
}

/**
 * Wraps a given region of the array.
 *
 * Viewer method.
 */
fun DoubleArray.asF64Array(offset: Int, size: Int): F64Array {
    return F64FlatArray(this, offset, 1, size)
}

/**
 * Copies the elements of this nested array into [F64Array] of the same shape.
 *
 * Copying method.
 */
fun Array<*>.toF64Array(): F64Array {
    val shape = guessShape()
    return flatten(this).asF64Array().reshape(*shape)
}

/** Flattens a nested [DoubleArray]. */
private fun flatten(a: Array<*>): DoubleArray {
    return Arrays.stream(a).flatMapToDouble {
        when (it) {
            is DoubleArray -> Arrays.stream(it)
            is Array<*> -> Arrays.stream(flatten(it))
            else -> unsupported() // unreachable since [guessShape] will fail faster
        }
    }.toArray()
}

/** No validation, therefore "check". */
private fun Array<*>.guessShape(): IntArray {
    check(isNotEmpty())
    return when (val tip = first()) {
        is DoubleArray -> {
            (1 until size).forEach {
                val el = get(it)
                check(el is DoubleArray) {
                    "array has elements of different types: element 0 is a double array, " +
                            "but element $it is ${el?.let { it::class.java.name } ?: "null"}"
                }
                check(el.size == tip.size) {
                    "array has elements of different sizes: element 0 is ${tip.size} long, " +
                            "but element $it is ${el.size} long"
                }
            }
            intArrayOf(size, tip.size)
        }
        is Array<*> -> {
            (1 until size).forEach {
                val el = get(it)
                check(el is Array<*>) {
                    "array has elements of different types: element 0 is a generic array, " +
                            "but element $it is ${el?.let { it::class.java.name } ?: "null"}"
                }
                check(el.size == tip.size) {
                    "array has elements of different sizes: element 0 is ${tip.size} long, " +
                            "but element $it is ${el.size} long"
                }
            }
            intArrayOf(size) + tip.guessShape()
        }
        else -> unsupported()
    }
}

/**
 * A special object used to denote all indices.
 *
 * @since 0.1.1 Renamed to `_I` because all-underscore names are reserved
 *              for internal use in Kotlin.
 */
@Suppress("ClassName")
object _I
