(ns clatrix.core
  (:refer-clojure :exclude [get set map-indexed map rand vector? + - * pp])
  (:use [slingshot.slingshot :only [throw+]])
  (:require [core.matrix.protocols :as mp]
            [core.matrix.implementations :as imp])
  (:import [org.jblas DoubleMatrix ComplexDoubleMatrix ComplexDouble
            Decompose Decompose$LUDecomposition Eigen Solve Geometry
            Singular MatrixFunctions]
           [org.jblas.util Random]
           [java.io Writer]))

;;; Clatrix is a fast matrix library for Clojure written atop [JBlas](http://jblas.org/)'
;;; ATLAS/LAPACK bindings. It's not intended to be the alpha and omega
;;; of linear algebra hosted on Clojure, but it should provide most of
;;; the basics for enabling more scientific/mathematical computation.

;;; # The clatrix matrix

;;; Matrix is implemented as a clojure Sequence data structure over JBlas'
;;; `DoubleMatrix` class. Many of the basic Clojure methods, such as
;;; `first`, `rest`, `cons`, and even some higher level functions, such as
;;; `map` and `reduce`, works with `Matrix`.
;;;
;;; The `Matrix` type is useful to operate on matrices in Clojure
;;; and to hide the underlying Java methods. It is not hard to access these
;;; Java methods (they're available through the `me` function), but their use is dissuaded.

(declare get permute size matrix matrix? row? nrows ncols vstack)

(deftype Matrix [^DoubleMatrix me ^Boolean vector? ^clojure.lang.IPersistentMap metadata]
  Object
  (toString [^Matrix mat]
    (str (list `matrix
               (vec (clojure.core/map vec (vec (.toArray2 ^DoubleMatrix (.me mat))))))))

  clojure.lang.IObj
  (withMeta [this metadata]
    (Matrix. me vector? metadata))
  (meta [this]
    metadata)

  clojure.lang.ISeq
  (equiv [this that]
    (cond
      (matrix? that) (.equals (.me this) (.me that))
      (coll? that) (and (= (count this) (count that))
                       (every? true? (clojure.core/map #(== %1 %2) this that)))
      (number? that) (and (= [1 1] (size this)) (= that (get this 0 0)))
      :else (.equals (.me this) that)))
  (first [this]
    (let [[r c] (size this)]
      (cond
        (or (zero? r) (zero? c)) nil
        (and vector? (or (= r 1) (= c 1))) (get this 0 0)
        :else (let [out (get this 0 (range c))]
                (if (number? out)
                  (matrix (vector out))
                  out)))))
  (more [this]
    (if-let [nxt (next this)]
      nxt
      (matrix [])))
  (cons [this x]
    (cond
      (matrix? x)  (vstack this x)
      (and (coll? x) (number? (first x))) (vstack this (matrix (vector x)))
      :else (vstack this (matrix x))))
  (seq [this]
    (let [[r c] (size this)]
      (when-not (or (zero? r) (zero? c))
        (Matrix. me vector? nil))))
  (next [this]
    (let [[r c] (size this)]
      (cond
        (and vector? (= 1 c) (> r 1)) (matrix (.me (get this (range 1 r) 0)) true nil)
        (and vector? (= 1 r) (> c 1)) (matrix (.me (get this 0 (range 1 c))) true nil)
        (and (not vector?) (> r 1)) (matrix (.me (get this (range 1 r) (range c))) false nil)
        :else nil)))
  (empty [this]
    (matrix []))

  clojure.lang.Counted
  (count [this]
    (if vector?
      (clojure.core/* (nrows this) (ncols this))
      (nrows this)))
  clojure.lang.Sequential)

(defn me [^Matrix mat]
  (.me mat))

(defmacro dotom [name m & args]
  `(~name ^DoubleMatrix (me ~m) ~@args))

;;; # Java interop
;;;
;;; Clatrix lifts a lot of methods directly out of JBlas. Here are a
;;; few convenience macros used for the thinnest parts of the wrapper.
(defmacro promote-cfun* [defname name fname]
  (let [n (gensym)
        m (gensym)]
    `(~defname ~name
               ([^long ~n] (matrix (~fname ~n)))
               ([^long ~n ~m] (matrix (~fname ~n ~m))))))

(defmacro promote-mfun* [defname name fname]
  (let [m (gensym)]
    `(~defname ~name [^Matrix ~m] (dotom ~fname ~m))))


;;; # Basics of matrix objects
;;;
;;; In linear algebra, matrices are two-dimensional arrays of
;;; doubles. The object `Matrix` is our particular instantiation.

(defn matrix?
  "`matrix?` tests whether an object is a `Matrix` object."
  [m] (isa? (class m) Matrix))

;;; The most fundamental question about a matrix is its size. This
;;; also defines a number of other ideas such as whether a matrix is a
;;; column or row vector. Columns are default, though, by convention,
;;; matrices are sometimes represented as nested seqs in row-major
;;; order.
(promote-mfun* defn ncols .columns)
(promote-mfun* defn nrows .rows)
(defn size    [^Matrix m] [(nrows m) (ncols m)])
(defn vector? [^Matrix m] (.vector? m))
(defn row?    [^Matrix m] (== 1 (first (size m))))
(defn column? [^Matrix m] (== 1 (second (size m))))
(defn square? [^Matrix m] (reduce == (size m)))

(defn- int-arraytise
  "Convert a seq to int array or pass through for a number."
  [x] (if (coll? x) (int-array x) x))

;;; The most basic matrix operation is elementwise getting and
;;; setting; setting should be dissuaded as well for a Clojure
;;; wrapper, but it's too useful to hide
;;;
;;; Example:
;;; <code><pre>
;;; (def M (matrix [[1 2] [3 4]]))
;;; (get M 1 1)  ;; 4.0
;;; (get M 0 [0 1]) ;; (matrix [[1 2]])
;;; (get M [0 1] [0 1]) ;; M
;;; </pre></code>

(defn get
  "Given matrix `m`, get row `r` and column `c`, where `r` and/or
  `c` can be either a value or vector to return a single value or
  a sub-matrix."
  [^Matrix m r c]
  (let [out (dotom .get m (int-arraytise r) (int-arraytise c))]
    (if (number? out)
      out
      (matrix out))))

(defn set [^Matrix m ^long r ^long c ^double e]
  (dotom .put m r c e))

;;; Already this is sufficient to get some algebraic matrix
;;; properties, such as

(defn trace
  "`trace` computes the trace of a matrix, the sum of its diagonal elements."
  [^Matrix mat]
  (if (square? mat)
    (let [[n _] (size mat)]
      (reduce #(clojure.core/+ (get mat %2 %2) %1) 0 (range n)))
    (throw+ {:error "Cannot take trace of non-square matrix."})))

;;; We can also map the entire matrices back into Clojure data
;;; structures like 2-nested vectors.

(defn dense
  "`dense` converts a matrix object into a seq-of-seqs of its elements
  in row-major order."  [^Matrix m]
  (vec (clojure.core/map vec (vec (dotom .toArray2 m)))))

(defn as-vec
  "`as-vec` converts a matrix object into a seq-of-seqs of its
  elements in row-major order. Treats `vector?` type matrices
  differently, though, flattening the return seq to a single vector."
  [^Matrix m]
  (if (vector? m)
    (vec (dotom .toArray m))
    (dense m)))

;;; # Matrix creation
;;;
;;; Matrices can be created from a number of simple specifications,
;;; such as (1) direct coercions, (2) element-constant matrices and
;;; vectors, and (3) identity matrices.

(defn column  ;; TODO remove from api
  "`column` coerces a seq of numbers to a column `Matrix`."
  [^doubles seq]
  (matrix seq))

(derive java.util.Collection ::collection)
(derive DoubleMatrix ::double-matrix)
(derive Matrix ::matrix)

(defmulti matrix
  "`matrix` creates a `Matrix` from a seq of seqs, specifying the
  matrix in row-major order. The length of each seq must be
  identical or an error is throw."
  (fn [m & args] (class m)))

(defmethod matrix ::matrix
  ([^Matrix m & _] m))

(defmethod matrix ::double-matrix
  ([^DoubleMatrix x]
   (matrix x (.isVector x) nil))
  ([^DoubleMatrix x vector? meta]
   (Matrix. x vector? meta)))

(defmethod matrix ::collection
  [seq-of-seqs]
  (if (number? (first seq-of-seqs))
    (matrix (DoubleMatrix. (into-array Double/TYPE (clojure.core/map double seq-of-seqs))))
    (let [lengths (clojure.core/map count seq-of-seqs)
          flen    (first lengths)]
      (cond
        (or (= (count lengths) 0) (some zero? lengths)) (matrix (DoubleMatrix. 0 0))
        (every? (partial = flen) lengths)
        (matrix
          (DoubleMatrix.
            ^"[[D" (into-array (clojure.core/map #(into-array Double/TYPE (clojure.core/map double %)) seq-of-seqs))))
        :else (throw+ {:error "Cannot create a ragged matrix."})))))

(defmethod matrix java.lang.Double
  [x]
  (matrix [x]))

(defmethod matrix java.lang.Long
  [x]
  (matrix [x]))

(defmethod matrix :default
  [m & _]
  nil)

(defn diag
  "`diag` creates a diagonal matrix from a seq of numbers or extracts
  the diagonal of a `Matrix` as a seq."
  [seq-or-matrix]
  (if (matrix? seq-or-matrix)
    (let [mat ^Matrix seq-or-matrix]
      ;; We'll extract largest diagonals from non-square matrices
      ;; since this isn't really a matrix algebraic property
      (let [n (apply min (size mat))]
        (clojure.core/map #(get mat % %) (range n))))
    (let [di (clojure.core/map double (seq seq-or-matrix))]
      (matrix (DoubleMatrix/diag (DoubleMatrix. ^doubles (into-array Double/TYPE di)))))))

(promote-cfun* defn  ones DoubleMatrix/ones) ;; TODO wrap inside matrix
(promote-cfun* defn zeros DoubleMatrix/zeros)

(defn constant
  "`constant` creates a column or matrix with every element equal to
  the same constant value."
  ([^long n ^double c]
   (matrix (doto (DoubleMatrix/ones n)
             (.muli c))))
  ([^long n ^long m ^double c]
   (matrix (doto (DoubleMatrix/ones n m)
             (.muli c)))))

(defn eye
  "`(eye n)` is the `n`x`n` identity matrix."
  [^long n] (matrix (DoubleMatrix/eye n)))

;;; ## Reshaping

(defn reshape
  "`(reshape A p q)` coerces an `n`x`m` matrix to be `p`x`q` so long
  as `pq = nm`."
  [^Matrix A p q]
  (let [[n m] (size A)]
    (if (= (clojure.core/* n m) (clojure.core/* p q))
      (dotom A p q)
      (throw+ {:exception "Cannot change the number of elements during a reshape."
               :previous (clojure.core/* n m)
               :new (clojure.core/* p q)}))))

;;; ## Sparse and indexed builds
;;;
;;; Sometimes your matrix is mostly zeros and it's easy to specify the
;;; matrix using only the non-zero entries. For this use
;;; `from-sparse`. It's also often easy to build matrices directly
;;; from their indices instead of going through an initial seq-of-seqs
;;; step which is facilitated by `from-indices`.

(defn from-sparse
  "`from-sparse` creates a new `Matrix` from a sparse
  representation. A sparse representation is a seq of seqs, each inner
  seq having the form `[i j v]` stating that in the final matrix
  `A`, `(get A i j)` is `v`. The sparse specifications are applied in
  order, so if they overlap the latter spec will override the prior
  ones."
  [^long n ^long m specs]
  (let [m (zeros n m)]
    (doseq [[i j v] specs]
      (set m i j v))
    m))

(defn from-indices
  "`from-indices` builds an `n`x`m` matrix from a function mapping the
  indices to values which are then stored at that location."
  [^long n ^long m fun]
  (let [ary (make-array Double/TYPE n m)]
    (doseq [i (range n)
            j (range n)]
      (aset ary i j (fun i j)))
    (matrix (DoubleMatrix. ^"[[D" ary))))

;;; ## Random matrices
;;;
;;; It's also useful to generate random matrices. These are
;;; elementwise independent Unif(0,1) and normal.
;;;
;;; You can use `seed`, `unseed`, and `with-seed` to make repeatable
;;; sequences of random numbers (for testing, for example,) similar
;;; to `seed(n)` in R.
;;;

(promote-cfun* defn  rand   DoubleMatrix/rand)
(promote-cfun* defn- randn* DoubleMatrix/randn)

(defn rnorm
  "`(rnorm mu sigma n m)` is an `n`x`m` `Matrix` with normally
  distributed random elements with mean `mu` and standard deviation
  `sigma`."
  ([^double mu ^double sigma ^long n ^long m]
   (matrix (doto (dotom .muli (randn* n m) sigma)
             (.addi mu))))
  ([^double mu ^double sigma ^long n]
   (matrix (doto (dotom .muli (randn* n) sigma)
             (.addi mu))))
  ([^long n ^long m] (randn* n m))
  ([^long n] (randn* n)))

(defn seed
  "You can set the seed used to generate the values in a random matrix if you
need repeatablity for some reason."
  [n]
  (Random/seed n))

(defonce ^:private uniquifier (atom 8682522807148012))

(defn unseed
  "When you are done changing the seed, you can go back to a pseudo-random seed.
Uses the same algorithm as java's default Random constructor."
  []
  (Random/seed (swap! uniquifier
                      #(bit-xor
                        (unchecked-multiply (long %) 181783497276652981)
                        (System/nanoTime)))))

(defmacro with-seed
  "If you want to control the scope of a seed you've set, you can use this convenient macro."
  [n & body]
  `(do
     (seed ~n)
     (try ~@body
          (finally
            (unseed)))))

;;; ## Element algebra
;;;
;;; Matrices can also be permuted, flipped, transposed, stacked, and
;;; split to form more complex matrices. This "element algebra" is a
;;; powerful way of building more complex matrices.

(defn t
  "`(t A)` is the transpose of `A`."
  [^Matrix mat] (matrix (dotom .transpose mat)))

(defn hstack
  "`hstack` concatenates a number of matrices by aligning them
  horizontally. Each matrix must have the same number of
  rows. Optionally, the any entry may be a seq, which is spliced into
  the arguments list via `flatten`, somewhat like the final argument
  to `apply`."
  [& vec-seq]
  (let [row-counts (clojure.core/map nrows vec-seq)
        rows (first row-counts)]
    (if (every? (partial == rows) row-counts)
      (matrix (reduce #(DoubleMatrix/concatHorizontally
                         ^DoubleMatrix %1
                         ^DoubleMatrix (me %2))
                      (me (first vec-seq))
                      (rest vec-seq))))))

(defn vstack
  "`vstack` is vertical concatenation in the style of `hstack`. See
  `hstack` documentation for more detail."
  [& vec-seq]
  (let [col-counts (clojure.core/map ncols vec-seq)
        cols (first col-counts)]
    (if (every? (partial == cols) col-counts)
      (matrix (reduce #(DoubleMatrix/concatVertically
                         ^DoubleMatrix %1
                         ^DoubleMatrix (me %2))
                      (me (first vec-seq))
                      (rest vec-seq))))))

(defn rows
  "`rows` explodes a `Matrix` into its constituent rows. By default,
  all the rows are returned in normal order, but particular indices
  can be specified by the second argument."
  ([^Matrix mat]   ;; TODO use map and get on datastruct
   (let [[n m] (size mat)]
     (rows mat (range n))))
  ([^Matrix m ^longs idxs]
   (clojure.core/map #(matrix (dotom .getRow m %)) idxs)))

(defn cols
  "`cols` explodes a `Matrix` into its constituent columns in the
  style of `rows`. See `rows` documentation for more detail."
  ([^Matrix mat]
   (cols mat (range (ncols mat))))
  ([^Matrix m ^longs idxs]
   (clojure.core/map #(matrix (dotom .getColumn m %)) idxs)))

;;; From this we also get generalized matrix permutations almost for free.

(defn- permute-rows [^Matrix mat rowspec]
  (if rowspec (apply vstack (rows mat rowspec)) mat))

(defn- permute-cols [^Matrix mat colspec]
  (if colspec (apply hstack (cols mat colspec)) mat))

(defn permute
  "`permute` permutes the rows and the columns of a matrix. `:rowspec`
  and `:colspec` (or, for short, `:r` and `:c`) are keyword arguments
  providing seqs listing the indices of the permutation."  [^Matrix
                                                            mat & {:keys [r c rowspec colspec]}]
  (let [[n m] (size mat)
        r (or r rowspec)
        c (or c colspec)]
    (cond (and r (some #(> % (dec n)) r))
          (throw+ {:error "Row index out of bounds" :num-rows n :rowspec r})
          (and c (some #(> % (dec m)) c))
          (throw+ {:error "Column index out of bounds" :num-columns n :colspec c})

          :else (permute-cols (permute-rows mat r) c))))

;;; ### Block matrices
;;;
;;; Block matrix syntax is a very convenient way of building larger
;;; matrices from smaller ones. Clatrix implements block matrix syntax
;;; in a convenient manner.
;;;
;;;     (block [[A 1 1 0]
;;;             [0 B . .]
;;;             [_ _ C .]
;;;             [_ _ D E]])
;;;
;;; Clatrix uses size constraint propagation to determine the proper
;;; sizes of the constant and 0 matrices.

(defn- iswild
  "`iswild` is a helper function that defines what a wildcard symbol
  is, used in `block`, `slice`, and `slices`."
  [sym]
  ;; This is a sort of silly way to do it, but I can't get the regex
  ;; to work for both '_ and #'user/_
  (let [name1 (second (re-find #"/(.+)" (str sym)))
        name2 (str sym)]
    (or (= name1 "_")
        (= name1 ".")
        (= name1 "*")
        (= name2 "_")
        (= name2 ".")
        (= name2 "*"))))

(defn- make-constr
  "`make-constr` is a subfunction for `block`. Generates the
  size-constraint map based on the elements of the seqs in a block
  matrix specification."
  [e]
  (if (matrix? e)
    {:matrix e
     :rows (first (size e))
     :cols (second (size e))}
    {:constant e}))

(defn- update-hash-with-constr
  "`update-hash-with-const` is a subfunction for `block`. It examines
  a particular constraint against an old hash representation of the
  full set of constraints, `hsh`. Updates the hash at position [i j]
  to respect the current constraint `constr` according to `key`"
  [hsh constr i j key]
  (let [{n key} constr
        old (hsh [i j])]
    (if (key old)
      (if (not= (key old) n) ;the constraint doesn't match the hash, uh oh
        (throw+ {:error "Block matrix diagram sizes are inconsistent."
                 :type :constraint-error
                 :location [i j]})
        hsh)
      ;; if there isn't an old key then we can fix that constraint now
      (assoc hsh [i j]
             (assoc old key n)))))

(defn block-fn
  "`block-fn` is the main worker subfunction for `block`. It's public
  so that `block` can macroexpand to call it. It creates a block
  matrix. Any number `n` represents the all-`n` matrix of an
  appropriate size to make the matrix."
  [matrices]
  ;; We must do size-constraint propagation along the rows and columns
  ;; of the block-diagram in order to (a) ensure that the input isn't
  ;; in error and (b) find the proper sizes for the constant matrices.
  (let [n       (count matrices)
        lengths (clojure.core/map count matrices)
        m       (first lengths)]
    (if (not (every? (partial == m) lengths))
      (throw+ {:error "Block matrices cannot be ragged."})

      ;; Build the constraints map
      (let [indices (for [i (range n) j (range m)] [i j])
            ;; The initial hash map contains what we know before
            ;; constraint propagation.
            init-map (reduce (fn [hsh [i j]]
                               (assoc hsh [i j]
                                      (make-constr (nth (nth matrices i) j))))
                             (hash-map) indices)
            ;; Walk over the index set and propagate all the constraints
            ;; over each row and column
            constraint-map
            (reduce
              (fn [hash [i j]]
                (let [constr (init-map [i j])]
                  (if (or (:rows constr) (:cols constr))
                    ;; Look up and to the left for constraint violations,
                    ;; locking in constraints if they don't already exist
                    (reduce #(update-hash-with-constr %1 constr i %2 :rows)
                            (reduce #(update-hash-with-constr %1 constr %2 j :cols)
                                    hash (range n))
                            (range m))
                    hash)))
              init-map
              indices)]
        ;; Use the constraint map to build the final matrix
        (apply vstack
               (for [i (range n)]
                 (apply hstack
                        (for [j (range m)]
                          (let [constr (constraint-map [i j])]
                            (if (:matrix constr)
                              (:matrix constr)
                              ;; Constants are assumed to be 1x1
                              ;; unless otherwise constrained
                              (constant (:rows constr 1)
                                        (:cols constr 1)
                                        (:constant constr))))))))))))

(defmacro block
  "`block` creates a block matrix using normal block matrix syntax
  written as a row-major ordered vector of vectors. Each entry in the
  specification can be a `Matrix`, a number, or a null symbol (either
  `.` or `_`). Numbers are translated as constant matrices of the
  appropriate size to complete the block matrix. Null symbols are
  considered as constant 0 matrices and are also automatically
  constrained to be the proper size. Any integers which do not share a
  row or a column with a larger matrix are assumed to be 1x1 sized."
  [blockspec]
  `(block-fn
     ~(vec (clojure.core/map
             #(vec (clojure.core/map
                     (fn [e] (if (iswild e) 0 e)) %))
             blockspec))))


;;; # Slicing
;;;
;;; As a more convenient API than looping over `get` and `set`, we
;;; have slice notation. This uses wildcard symbols (like in `block`)
;;; in order to represent full index sets.
;;;
;;;     (slice A _ 5) ; ==> the whole 5th column
;;;     (slice A 4 5) ; ==> (get A 4 5)
;;;
;;; The slice macro also overloads setters. For instance
;;;
;;;     (slice A _ 1 (column (range 10)))
;;;
;;; replaces the 2nd column of `A` with the numbers 0 through 9.

(defn- slicer
  ([^Matrix matrix rowspec colspec]
   (cond (and (iswild rowspec) (iswild colspec)) matrix
         (iswild rowspec) `(matrix (dotom .getColumn ~matrix ~colspec))
         (iswild colspec) `(matrix (dotom .getRow    ~matrix ~rowspec))
         :else            `(get ~matrix ~rowspec ~colspec)))
  ([^DoubleMatrix matrix rowspec colspec values]
   (let [m (gensym)
         form (cond (and (iswild rowspec) (iswild colspec)) `(.copy ~m ~values)
                    (iswild rowspec) `(dotom .putColumn ~m ~colspec ~values)
                    (iswild colspec) `(dotom .putRow    ~m ~rowspec ~values)
                    :else            `(set        ~m ~rowspec ~colspec ~values))]
     `(let [~m ~matrix]
        (do ~form ~m)))))

(defmacro slice
  "`slice` is the primary function for accessing and modifying a
  matrix at the single row, column, entry, or full matrix level. The
  row/colspec variables are either an integer or the atom `'_`
  signifying that the index should run over all possible values for
  the row or column index. If a fourth argument is passed it is
  assumed to be a size-conforming entry, row, or matrix to be
  inserted into the spec'd location."
  [^Matrix matrix rowspec colspec & values?]
  (apply slicer matrix rowspec colspec values?))

(defmacro slices
  "`slices` provides an identical interface to `slice` except that it
  returns a seq (or seq-of-seqs) instead of a `Matrix`."
  [^Matrix matrix rowspec colspec & values?]
  `(as-vec ~(apply slicer matrix rowspec colspec values?)))

;;; # Hinting
;;;
;;; Many more complex matrix operations can be specialized for certain
;;; kinds of matrices. In particular, symmetric and positive definite
;;; matrices are much easier to handle. Clatrix doesn't natrually know
;;; which matrices have these special properties, but hinting
;;; functions can be used to assert that certain matrices are indeed
;;; symmetric or positive definite.
;;;
;;; Most operations in Clatrix create new objects, thus the assertions
;;; do not propagate. If they are, however, they can be removed by
;;; asserting the matrix `arbitrary`.

(defn symmetric
  "`symmetric` asserts that a matrix is symmetric."
  [^Matrix m] (with-meta m {:symmetric true}))

(defn positive
  "`positive` asserts that a matrix is positive definite. By definition, it is also `symmetric`."
  [^Matrix m] (with-meta m {:symmetric true :positive true}))

(defn arbitrary
  "`arbitrary` asserts that a matrix is just arbitrary."
  [^Matrix m] (with-meta m {:symmetric false :positive false}))

(defn symmetric? [^Matrix m] (:symmetric (meta m)))
(defn positive?  [^Matrix m] (:positive (meta m)))
(defn arbitrary? [^Matrix m] (not (or (symmetric? m) (positive? m))))

(defn maybe-symmetric
  "`maybe-symmetric` attempts to assert that a matrix is symmetric,
  but only succeeds if it actually is."
  [^Matrix m]
  (if (or (symmetric? m) (= (t m) m))
    (symmetric m)
    (with-meta m {:symmetric false})))

(defn maybe-positive
  "`maybe-positive` attempts to assert that a matrix is positive definite,
  but only succeeds if it actually is. (Checked via eigenvalue
  positivity.)"
  [^Matrix m]
  (let [m (maybe-symmetric m)]
    (if (symmetric? m)
      ;; We'll have faster access to the eigenvalues later...
      (let [vals (dotom Eigen/eigenvalues m)
            rvals (seq (.toArray (.real vals)))
            ivals (seq (.toArray (.real vals)))
            mags (clojure.core/map #(Math/sqrt
                                      (clojure.core/+ (Math/pow %1 2)
                                                      (Math/pow %2 2))) rvals ivals)]
        (if (every? #(> % 0) mags)
          (positive m)
          m))
      m)))

;;; # Functor operations
;;;
;;; It's sometimes useful to transform a matrix elementwise. For this
;;; we use the usual functor interface through `map-indexed` and
;;; `map`.

(defn map-indexed
  "`map-indexed` maps a function over the indices and corresponding
  elements to create a new equivalently sized matrix with the
  resulting elements."
  [fun ^Matrix mat]
  (let [[n m] (size mat)]
    (from-sparse n m
                 (for [i (range n)
                       j (range m)]
                   [i j (fun i j (get mat i j))]))))

(defn map
  "`map` is a specialization of `map-indexed` where the function does
  not get passed the indices."
  [fun ^Matrix mat]
  (let [[n m] (size mat)]
    (from-sparse n m
                 (for [i (range n)
                       j (range m)]
                   [i j (fun (get mat i j))]))))

;; (last (for ...)) seems like a strange
(defn map!
  "Inplace version of map."
  [fun ^Matrix mat]
  (let [[n m] (size mat)]
    (last
     (for [i (range n)
           j (range m)]
       (set mat i j (fun (get mat i j)))))))

(defn ereduce
  "Quick and dirty reduce."
  [fun ^Matrix mat]
  (reduce fun (flatten mat)))


;;; # Linear algebra
;;;
;;; Some of the most important reasons one would use matrices comes
;;; from the techniques of linear algebra. Considering matrices as
;;; linear transformations of vector spaces gives meaning to matrix
;;; multiplication, introduces inversion and linear system solving,
;;; and introduces decompositions and spectral theory. The following
;;; functions give access to these rich techniques.

(defn norm
  "`norm` computes the 2-norm of a vector or the Frobenius norm of a matrix."
  [^Matrix mat]
  (dotom .norm2 mat))

(defn normalize
  "`normalize` normalizes a matrix as a single column or collection of
  column vectors."
  [^Matrix mat & [flags]]
  (if (column? mat)
    (matrix (dotom Geometry/normalize mat))
    (matrix (dotom Geometry/normalizeColumns mat))))

(defn +
  "`+` sums vectors and matrices (and scalars as if they were constant
  matrices). All the matrices must have the same size."
  ([a b] (cond (and (matrix? a) (matrix? b))
               (if (= (size a) (size b))
                 (matrix (dotom .add a ^DoubleMatrix (me b)))
                 (throw+ {:exception "Matrices of different sizes cannot be summed."
                          :asize (size a)
                          :bsize (size b)}))
               (matrix? a) (matrix
                             (dotom .add a (double b)))
               (matrix? b) (matrix
                             (dotom .add b (double a)))
               :else       (clojure.core/+ a b)))
  ([a b & as] (reduce + a (cons b as))))

(defn *
  "`*` computes the product of vectors and matrices (and scalars as
  scaling factors). All matrices must have compatible sizes."
  ([a b] (cond (and (matrix? a) (matrix? b))
               (if (= (second (size a)) (first (size b)))
                 (matrix (dotom .mmul a ^DoubleMatrix (me b)))
                 (throw+ {:exception "Matrix products must have compatible sizes."
                          :a-cols (ncols a)
                          :b-rows (nrows b)}))
               (matrix? a) (matrix
                             (dotom .mmul a (double b)))
               (matrix? b) (matrix
                             (dotom .mmul b (double a)))
               :else       (clojure.core/* a b)))
  ([a b & as] (reduce * a (cons b as))))

(defn mult
  "Element-wise multiplication."
  ([a b] (cond (and (matrix? a) (matrix? b))
               (matrix (dotom .mul a ^DoubleMatrix (me b)))
               (matrix? a) (matrix
                             (dotom .mul a (double b)))
               (matrix? b) (matrix
                             (dotom .mul b (double a)))
               :else       (clojure.core/* a b))))

(defn div
  "Element-wise division."
  ([a b] (cond (and (matrix? a) (matrix? b))
               (matrix (dotom .div a ^DoubleMatrix (me b)))
               (matrix? a) (matrix
                             (dotom .div a (double b)))
               (matrix? b) (matrix
                             (dotom .rdiv b (double a)))
               :else       (clojure.core// a b))))

(defn -
  "`-` differences vectors and matrices (and scalars as if they were
  constant matrices). All the matrices must have the same size."
  ([a] (* -1 a))
  ([a b] (cond (and (matrix? a) (matrix? b))
               (if (= (size a) (size b))
                 (matrix (dotom .sub a ^DoubleMatrix (me b)))
                 (throw+ {:exception "Matrices of different sizes cannot be differenced."
                          :asize (size a)
                          :bsize (size b)}))
               (matrix? a) (matrix
                             (dotom .sub a (double b)))
               (matrix? b) (matrix
                             (dotom .rsub b (double a)))
               :else       (clojure.core/- a b)))
  ([a b & as] (reduce - a (cons b as))))

(defn dot
  "`dot` computes the inner product between two vectors. This is
  extended to work on matrices considered as `nm`-dimensional
  vectors."
  [^Matrix m1 ^Matrix m2]
  (dotom .dot m1 ^DoubleMatrix (me m2)))

;;; We can also create random matrices of particular classes. For
;;; instance, `rspectral` allows for the creation of random square
;;; matrices with particular spectra.

(defn rreflection
  "`(rreflection n)` creates a random `n`-dimensional Householder
  reflection."
  [n]
  (let [v (Geometry/normalize (DoubleMatrix/randn n))]
    (matrix
      (.sub (DoubleMatrix/eye n) (.mmul (.mmul v (.transpose v)) (double 2))))))

(defn rspectral
  "`rspectral` creates a random matrix with a particular spectrum, or,
  if only an integer `n` is passed, then it creates a random `n`x`n`
  positive definite matrix with a random spectrum. The orthogonal
  matrices are generated by using `2n` composed Householder
  reflections."
  [n-or-spectrum]
  (let [[n spectrum]
        (if (sequential? n-or-spectrum)
          [(count n-or-spectrum) n-or-spectrum]
          [n-or-spectrum (repeatedly n-or-spectrum clojure.core/rand)])

        V ^DoubleMatrix
        (nth (iterate (fn [^DoubleMatrix prod]
                        (.mmul prod ^DoubleMatrix (me (rreflection n))))
                      (DoubleMatrix/eye n))
             (clojure.core/* 2 n))

        L (DoubleMatrix/diag
            (DoubleMatrix.
              ^doubles (into-array Double/TYPE spectrum)))
        A (matrix (-> V (.mmul L) (.mmul (.transpose V))))]
    (if (every? pos? spectrum)
      (positive A)
      A)))

(defn solve
  "`solve` solves the equation `Ax = B` for the column `Matrix`
  `x`. Positivity and symmetry hints on `A` will cause `solve` to use
  optimized LAPACK routines."
  [^Matrix A ^Matrix B]
  (matrix
    (cond
      (positive? A)  (Solve/solvePositive ^DoubleMatrix (me A)
                                          ^DoubleMatrix (me B))
      (symmetric? A) (Solve/solveSymmetric ^DoubleMatrix (me A)
                                           ^DoubleMatrix (me B))
      :else          (Solve/solve ^DoubleMatrix (me A)
                                  ^DoubleMatrix (me B)))
    ))

(defn i
  "`i` computes the inverse of a matrix. This is done via Gaussian
  elmination through the `solve` function. It can be numerically very
  unstable if the matrix is nearly singular."
  [^Matrix mat]
  (if (not (square? mat))
    (throw+ {:exception "Cannot invert a non-square matrix."})
    (let [[n _] (size mat)]
      (solve mat (eye n)))))

(defn eigen
  "`eigen` computes the eigensystem (or generalized eigensystem) for a
  square `Matrix` `A`. Type hinting on `A` uses optimized routines for
  symmetric matrices while `(eigen A B)` will check to ensure `A` and
  `B` are symmetric before computing the generalized eigenvectors `x`
  such that `A x = L B x`. In non-symmetric computations, eigenvalues
  and vectors may be complex! In all cases, the real parts are
  returned as keys `:values` and `:vectors` in the output hash map,
  but if imaginary parts exist they are stored in `:ivalues` and
  `:ivectors`. Inproper symmetry hinting or failure to check for
  imaginary values will lead to mistakes in using the matrix
  spectrum."
  ([^Matrix A]
   (cond
     (symmetric? A) (let [[vecs vals] (clojure.core/map #(matrix %)
                                                        (seq (dotom Eigen/symmetricEigenvectors A)))]
                      {:vectors vecs :values (diag vals)})
     :else          (let [[^ComplexDoubleMatrix vecs ^ComplexDoubleMatrix vals]
                          (seq (dotom Eigen/eigenvectors A))
                          rvecs (matrix (.real vecs))
                          ivecs (matrix (.imag vecs))
                          rvals (diag (matrix (.real vals)))
                          ivals (diag (matrix (.imag vals)))
                          out {:vectors rvecs
                               :values  rvals}]
                      (if (some (partial not= 0.0) ivals)
                        (merge out
                               {:ivectors ivecs
                                :ivalues  ivals})
                        out))))
  ([^Matrix A ^Matrix B]
   (let [A (maybe-symmetric A)
         B (maybe-symmetric B)]
     (if (and (symmetric? A) (symmetric? B))
       (let [[vecs vals]
             (clojure.core/map #(matrix %)
                               (seq (Eigen/symmetricGeneralizedEigenvectors ^DoubleMatrix (me A)
                                                                            ^DoubleMatrix (me B))))]
         {:vectors vecs :values (as-vec vals)})
       (throw+ {:error "Cannot do generalized eigensystem for non-symmetric matrices."})))))

(defn svd
  "`(svd A)` computes the sparse singular value decomposition of `A`
  returning a map with keys `{:values L :left U :right V}` such that
  `A = U (diag L) V`. If `(size A)` is `[n m]` and the rank of `A` is
  `k`, we have the size of `U` as `[n k]`, `(diag L)` as `[k k]`,
  and `(t V)` as `[k m]`.

  Optional key:
  `:type` --  `:full` for the full SVD
              `:value` for SVD values only
  "
  [^Matrix A & {:keys [type] :or {type :sparse}}]
  (let [[U L V] (if (= type :full)
                  (dotom Singular/fullSVD A)
                  (dotom Singular/sparseSVD A))
        left (matrix U)
        right (matrix V)
        values (seq (.toArray L))]
    (if (= type :values)
      {:values (seq (.toArray (dotom Singular/SVDValues A)))}
      {:left left
       :right right
       :values values
       :rank (count values)})))

(defn rank
  "`(rank A)` is the rank of matrix `A` as computed by `svd`."
  [^Matrix A] (:rank (svd A)))

(defn- complex-power
  "`complex-power` computes `z^e` for some complex number `z` and
  double exponent `e`."
  [^ComplexDouble z ^double e]
  (let [r (.real z)
        i (.imag z)
        m (Math/sqrt (clojure.core/+ (Math/pow r 2) (Math/pow i 2)))
        a (Math/atan2 i r)
        m2 (Math/pow m e)
        a2 (clojure.core/* a e)]
    (ComplexDouble. (clojure.core/* m2 (Math/cos a2))
                    (clojure.core/* m2 (Math/sin a2)))))

(defn pow
  "`(pow A e)` computes the `e`th matrix power of the square matrix
  `A` using Eigendecomposition. `e` need not be an integer."
  [^Matrix A e]
  (if (not (square? A))
    (throw+ {:exception "Cannot take power of non-square matrix."
             :size (size A)})
    (let [[n _] (size A)

          [^ComplexDoubleMatrix V
           ^ComplexDoubleMatrix L]
          (seq (dotom Eigen/eigenvectors A))

          new-spect (clojure.core/map #(complex-power % e)
                                      (seq (.toArray (.diag L))))]
      ;; Update L with the new spectrum
      (doseq [i (range n)]
        (.put L i i (nth new-spect i)))
      ;; Compute the product
      (-> V
        (.mmul L)
        (.mmul (.transpose V))
        .real
        (matrix)))))

(defn cholesky
  "`(cholesky A)` is the Cholesky square root of a matrix, `U` such
  that `U' U = A`. Note that `A` must be positive (semi) definite for
  this to exist, but `cholesky` requires strict positivity."
  [^Matrix mat]
  (let [mat (maybe-positive mat)]
    (if (positive? mat)
      (matrix (dotom Decompose/cholesky mat))
      (throw+ {:exception "Cholesky decompositions require positivity."}))))

(defn lu
  "`(lu A)` computes the LU decomposition of `A`, returning `{:p P :l L :u
  U}` such that `A = PLU`."
  [^Matrix mat]
  (if (not (square? mat))
    (throw+ {:exception "Cannot compute LU decomposition of a non-square matrix."
             :size (size mat)})
    (let [lu (dotom Decompose/lu mat)]
      {:p (matrix ^DoubleMatrix (.p lu))
       :l (matrix ^DoubleMatrix (.l lu))
       :u (matrix ^DoubleMatrix (.u lu))})))

;;; # Printing the matrices
;;;
;;; When normally working with matrices in a REPL, it's huge mistake
;;; to accidentally print a large matrix to the terminal. Usually,
;;; it's sufficient to know a few matrix properties to check your
;;; process so long as other functions allow for more detailed
;;; examination.
;;;
;;; As seen above, `(str A)` on some matrix `A` attempts to write a
;;; readable lisp form for `A` by using dense seq-of-seq
;;; semantics. Below we demonstrate non-readable printing methods for
;;; `Matrix` which show only its size (`print-method`, used by the
;;; REPL).

;;; TODO (This function is pretty ugly...)

(defmethod print-method Matrix [mat ^java.io.Writer w]
  (let [[nbits prec] [3 2]
        [n m] (size mat)
        small-rows (< n (* nbits 2))
        small-cols (< m (clojure.core/* nbits 2))
        rowset (if (< n (clojure.core/* nbits 2))
                 (range n)
                 (concat (range nbits) (range (clojure.core/- n nbits) n)))
        colset (if (< m (clojure.core/* nbits 2))
                 (range m)
                 (concat (range nbits) (range (clojure.core/- m nbits) m)))
        submat (apply hstack (cols (apply vstack (rows mat rowset)) colset))
        [n m] (size submat)
        fmt (str "% ." prec "e ")
        header (apply format " A %dx%d matrix\n" (size mat))]
    ;; Print the header
    (.write w header)
    (.write w " ")
    (doseq [i (range (dec (count header)))] (.write w "-"))
    (.write w "\n")
    ;; Print the matrix
    (if small-rows
      (doseq [i (range n)]
        (if small-cols
          (doseq [j (range m)]
            (.write w (format fmt (slice submat i j))))
          (do (doseq [j (range nbits)]
                (print (format fmt (slice submat i j))))
            (.write w " . ")
            (doseq [j (range nbits (clojure.core/* 2 nbits))]
              (.write w (format fmt (slice submat i j))))))
        (.write w "\n"))
      (do (doseq [i (range nbits)]
            (if small-cols
              (doseq [j (range m)]
                (.write w (format fmt (slice submat i j))))
              (do (doseq [j (range nbits)]
                    (.write w (format fmt (slice submat i j))))
                (.write w " . ")
                (doseq [j (range nbits (clojure.core/* 2 nbits))]
                  (.write w (format fmt (slice submat i j))))))
            (.write w "\n"))
        (.write w " ... \n")
        (doseq [i (range nbits (clojure.core/* 2 nbits))]
          (if small-cols
            (doseq [j (range m)]
              (.write w (format fmt (slice submat i j))))
            (do (doseq [j (range nbits)]
                  (.write w (format fmt (slice submat i j))))
              (.write w " . ")
              (doseq [j (range nbits (clojure.core/* 2 nbits))]
                (.write w (format fmt (slice submat i j))))))
          (.write w "\n"))))))

;;; # matrix-api
;;;
;;; Extend Matrix type to implement matrix-api protocols
;;; Note that the matrix-api notion of a vector conflicts with
;;; our notion of vector.  Essentially, all our vectors are
;;; considered matrix-row or matrix-column
(extend-type Matrix
  mp/PImplementation
  (implementation-key [m]
    :clatrix)
  (construct-matrix   [m data]
    (matrix data))
  (new-vector         [m length]
    (throw (UnsupportedOperationException. "Clatrix only support 2-d")))
  (new-matrix         [m rows columns]
    (zeros rows columns))
  (new-matrix-nd      [m shape]
    (throw (UnsupportedOperationException. "Clatrix only support 2-d")))
  (supports-dimensionality? [m dimensions]
    (<= dimensions 2))

  mp/PDimensionInfo
  (dimensionality [m] (cond (some zero? (size m)) 0
                            (vector? m)           2
                            :else                 2))
  (get-shape  [m] (filter (partial < 1) (size m)))
  (is-scalar? [m] (= [1 1] (size m)))
  (is-vector? [m] false #_(vector? m))
  (dimension-count [m dimension-number] (let [[r c] (size m)]
                                          (condp = dimension-number
                                            0 r
                                            1 c
                                            nil)))

  mp/PIndexedAccess
  (get-1d [m i] (cond
                  (and (vector? m) (row? m)) (get m 0 i)
                  (and (vector? m) (column? m)) (get m i 0)
                  :else (throw (IllegalArgumentException.  "Not a vector"))))
  (get-2d [m row column] (get m row column))
  (get-nd [m indexes] (throw (UnsupportedOperationException. "Clatrix only support 2-d")))

  mp/PIndexedSetting
  (set-1d [m i x] (cond
                      (and (vector? m) (row? m)) (set m 0 i x)
                      (and (vector? m) (column? m)) (set m i 0 x)
                      :else (throw (IllegalArgumentException.  "Not a vector"))))
  (set-2d [m row column x] (set m row column x))
  (set-nd [m indexes x] (throw (UnsupportedOperationException. "Clatrix only support 2-d")))
  (is-mutable? [m] false)

  mp/PMatrixCloning
  (clone [m] (matrix m))

  mp/PMatrixSlices
  (get-row [m i]
    (slice m i _))
  (get-column [m i]
    (slice m _ i))
  (get-major-slice [m i]
    (slice m i _))
  (get-slice [m dimension i]
    (condp = dimension
      0 (slice m i _)
      1 (slice m _ i))
    (throw (UnsupportedOperationException. "Clatrix only support 2-d")))

  mp/PConversion
  (convert-to-nested-vectors [m]
    (as-vec m))

  mp/PFunctionalOperations
  (element-seq [m]
    (flatten m))
  (element-map [m f]
    (map f m))
  (element-map! [m f]
    (map f m))
  (element-reduce [m f]
    (ereduce f m))

  mp/PMatrixMultiply
  (matrix-multiply [m a]
    (* (matrix m) (matrix a)))
  (element-multiply [m a]
    (mult (matrix m) a))

  ;; ---------------------------------------------------------------------------
  ;;  Optional protocols
  mp/PTypeInfo
  (element-type [m]
    java.lang.Double)

  mp/PSpecialisedConstructors
  (identity-matrix [m dims]
    (eye dims))
  (diagonal-matrix [m values]
    (diag values))

  mp/PCoercion
  (coerce-param [m param]
    (matrix param))
  )

;;; Register the implementation with core.matrix
(imp/register-implementation (zeros 2 2))

;;;  # Native math operators
;;;
;;;  JBLAS provides several fast and useful mathematical functions,
;;;  mirroring Java's Math namespace, applied
;;;  elementwise to the Matrix.  Here we import them.
;;;  They are provided in a funcional form
;;;  as well as a mutating form, the latter have i (for in-place)
;;;  appended to their names.

;;; Helper macro
(defmacro promote-mffun* [defname name fname]
  (let [m (gensym)]
    `(~defname ~name ^Matrix [^Matrix ~m] (matrix (dotom ~fname ~m)))))

;;; These are the functional style matrix functions.  They accept a
;;; matrix and return a new matrix with the function applied element-wise.
(promote-mffun* defn exp    MatrixFunctions/exp)
(promote-mffun* defn abs    MatrixFunctions/abs)
(promote-mffun* defn acos   MatrixFunctions/acos)
(promote-mffun* defn asin   MatrixFunctions/asin)
(promote-mffun* defn atan   MatrixFunctions/atan)
(promote-mffun* defn cbrt   MatrixFunctions/cbrt)
(promote-mffun* defn ceil   MatrixFunctions/ceil)
(promote-mffun* defn cos    MatrixFunctions/cos)
(promote-mffun* defn cosh   MatrixFunctions/cosh)
(promote-mffun* defn exp    MatrixFunctions/exp)
(promote-mffun* defn floor  MatrixFunctions/floor)
(promote-mffun* defn log    MatrixFunctions/log)
(promote-mffun* defn log10  MatrixFunctions/log10)
(promote-mffun* defn signum MatrixFunctions/signum)
(promote-mffun* defn sin    MatrixFunctions/sin)
(promote-mffun* defn sinh   MatrixFunctions/sinh)
(promote-mffun* defn sqrt   MatrixFunctions/sqrt)
(promote-mffun* defn tan    MatrixFunctions/tan)
(promote-mffun* defn tanh   MatrixFunctions/tanh)

;;; These are the inplace functions.  They mutate the passed
;;; in matrix with the function.  They are faster
(promote-mffun* defn exp!    MatrixFunctions/expi)
(promote-mffun* defn abs!    MatrixFunctions/absi)
(promote-mffun* defn acos!   MatrixFunctions/acosi)
(promote-mffun* defn asin!   MatrixFunctions/asini)
(promote-mffun* defn atan!   MatrixFunctions/atani)
(promote-mffun* defn cbrt!   MatrixFunctions/cbrti)
(promote-mffun* defn ceil!   MatrixFunctions/ceili)
(promote-mffun* defn cos!    MatrixFunctions/cosi)
(promote-mffun* defn cosh!   MatrixFunctions/coshi)
(promote-mffun* defn exp!    MatrixFunctions/expi)
(promote-mffun* defn floor!  MatrixFunctions/floori)
(promote-mffun* defn log!    MatrixFunctions/logi)
(promote-mffun* defn log10!  MatrixFunctions/log10i)
(promote-mffun* defn signum! MatrixFunctions/signumi)
(promote-mffun* defn sin!    MatrixFunctions/sini)
(promote-mffun* defn sinh!   MatrixFunctions/sinhi)
(promote-mffun* defn sqrt!   MatrixFunctions/sqrti)
(promote-mffun* defn tan!    MatrixFunctions/tani)
(promote-mffun* defn tanh!   MatrixFunctions/tanhi)

;;; TODO: pow is more complex and not currenty supported
