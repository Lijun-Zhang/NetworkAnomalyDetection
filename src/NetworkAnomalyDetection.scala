/**
 * Anomaly Detection in Network Traffic with different clustering algorithm.
 *
 * The implementation is done using the DataFrame-based API of SparkMLlib.
 *
 * Algorithms:
 *
 *  - K-means
 *  - Gaussian Mixture Model (GMM)
 *
 * Categorical features are transformed into numerical features using one-hot encoder.
 * Afterwards, all features are normalized.
 *
 * These different implementation are compared.
 *
 * Metrics used:
 *
 *  - Sum of distances between points and their centroids
 *
 * GMM is really slow (quadratic algorithm), so the performance will only be done on 1% of the dataset.
 *
 * Basic implementation is based on the chapter 5 (Anomaly Detection in Network Traffic with K-means clustering)
 * of the book Advanced Analytics with Spark.
 * However, this implementation is using the DataFrame-based API instead of the RDD-based API.
 *
 * Anomaly detection is done as follow:
 *
 *   - Find the maximal value of each cluster, those will be the thresholds
 *   - For a new point, calculate its score (distance), if it is more than the threshold of its cluster,
 *     this is an anomaly
 *
 * Datasource: https://archive.ics.uci.edu/ml/datasets/KDD+Cup+1999+Data
 * Test set:  http://kdd.ics.uci.edu/databases/kddcup99/kddcup99.html (corrected.gz)
 *
 * @author Axel Fahy
 * @author Rudolf Höhn
 * @author Brian Nydegger
 * @author Assaf Mahmoud
 *
 * @date 26.05.2017
 *
 */

import java.io.{File, PrintWriter}
import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types._
import org.apache.spark.ml.clustering._
import org.apache.spark.ml.feature.{OneHotEncoder, StandardScaler, StringIndexer, VectorAssembler}
import org.apache.spark.ml.linalg.{DenseVector, Vector}
import org.apache.spark.sql.functions._


object NetworkAnomalyDetection {

  val DataPath = "data/kddcup.data.corrected"
  val TestPath = "data/test.data.corrected"

  // Fraction of the dataset used (1.0 for the full dataset)
  val Fraction = 1.0

  // Schema of data from csv file
  // Used when loading the data to have a correct structure
  val DataSchema = StructType(Array(
    StructField("duration", IntegerType, true),
    StructField("protocol_type", StringType, true),
    StructField("service", StringType, true),
    StructField("flag", StringType, true),
    StructField("src_bytes", IntegerType, true),
    StructField("dst_bytes", IntegerType, true),
    StructField("land", IntegerType, true),
    StructField("wrong_fragment", IntegerType, true),
    StructField("urgent", IntegerType, true),
    StructField("hot", IntegerType, true),
    StructField("num_failed_logins", IntegerType, true),
    StructField("logged_in", IntegerType, true),
    StructField("num_compromised", IntegerType, true),
    StructField("root_shell", IntegerType, true),
    StructField("su_attempted", IntegerType, true),
    StructField("num_root", IntegerType, true),
    StructField("num_file_creations", IntegerType, true),
    StructField("num_shells", IntegerType, true),
    StructField("num_access_files", IntegerType, true),
    StructField("num_outbound_cmds", IntegerType, true),
    StructField("is_host_login", IntegerType, true),
    StructField("is_guest_login", IntegerType, true),
    StructField("count", IntegerType, true),
    StructField("srv_count", IntegerType, true),
    StructField("serror_rate", DoubleType, true),
    StructField("srv_serror_rate", DoubleType, true),
    StructField("rerror_rate", DoubleType, true),
    StructField("srv_rerror_rate", DoubleType, true),
    StructField("same_srv_rate", DoubleType, true),
    StructField("diff_srv_rate", DoubleType, true),
    StructField("srv_diff_host_rate", DoubleType, true),
    StructField("dst_host_count", IntegerType, true),
    StructField("dst_host_srv_count", IntegerType, true),
    StructField("dst_host_same_srv_rate", DoubleType, true),
    StructField("dst_host_diff_srv_rate", DoubleType, true),
    StructField("dst_host_same_src_port_rate", DoubleType, true),
    StructField("dst_host_srv_diff_host_rate", DoubleType, true),
    StructField("dst_host_serror_rate", DoubleType, true),
    StructField("dst_host_srv_serror_rate", DoubleType, true),
    StructField("dst_host_rerror_rate", DoubleType, true),
    StructField("dst_host_srv_rerror_rate", DoubleType, true),
    StructField("label", StringType, true)))

  def main(args: Array[String]): Unit = {
    // Creation of configuration and session
    val conf = new SparkConf()
      .setMaster("local")
      .setAppName("NetworkAnomalyDetection")
      .set("spark.driver.memory", "6g")

    val sc = new SparkContext(conf)
    sc.setCheckpointDir("checkpoints/")

    val spark = SparkSession
      .builder()
      .appName("NetworkAnomalyDetection")
      .getOrCreate()

    // Load the data into the schema created previously
    val rawDataDF = spark.read.format("com.databricks.spark.csv")
      .option("header", "false")
      .option("inferSchema", "true")
      .schema(DataSchema)
      .load(DataPath)

    val dataDF = rawDataDF.sample(false, Fraction, 42)
    println("Size of dataset=" + dataDF.count + " (total=" + rawDataDF.count + ")")
    val runClustering = new RunClustering(spark, dataDF)

    // K-means
    // K-means simple is also doing anomaly detections.
    (20 to 100 by 10).map(k => (k, runClustering.kmeansSimple(k)))
    (20 to 100 by 10).map(k => (k, runClustering.kmeansOneHotEncoder(k)))
    (20 to 100 by 10).map(k => (k, runClustering.kmeansOneHotEncoderWithNormalization(k)))

    // Bisecting K-means
    (20 to 100 by 10).map(k => (k, runClustering.bisectingKmeansOneHotEncoderWithNormalization(k)))

    // Gaussian Mixture
    (20 to 100 by 10).map(k => (k, runClustering.gaussianMixtureOneHotEncoderWithNormalization(k)))
  }

  class RunClustering(private val spark: SparkSession, var data: DataFrame) {

    import spark.implicits._

    // Select only numerical features
    val CategoricalColumns = Seq("label", "protocol_type", "service", "flag")

    /**
      * Calculate the Euclidean distance between a data point and its centroid
      *
      * @param centroid Vector with the components of the centroid
      * @param data Vector with the components of the data point
      * @return The distance between the data point and the centroid
      */
    def distance(centroid: Vector, data: Vector): Double =
      // Tranforming vector to array of double since operations
      // on vector are not implemented
      math.sqrt(centroid.toArray.zip(data.toArray)
        .map(p => p._1 - p._2).map(d => d * d).sum)

    /**
      * Apply the Euclidean distance between all points belonging to a centroid and the centroid in question
      *
      * @param centroid Vector with the components of the centroid
      * @param dataCentroid All data points (as Vector) belonging to the centroid
      * @return An array of double containing all the distance of a cluster (data with same centroid)
      */
    def distanceAllCluster(centroid: Vector, dataCentroid: Array[DenseVector]): Array[Double] = {
      dataCentroid.map(d => distance(centroid, d))
    }

    /**
      * Calculate the score of a cluster
      *
      * For each k, select data belonging to the centroid
      * and calculating the distance.
      *
      * @param centroids Array containing all the centroids
      * @param data Dataset used
      * @param k Number of cluster
      * @return The mean of the score from all cluster
      */
    def clusteringScore(centroids: Array[Vector], data: DataFrame, k: Int): Double = {
      val score = (0 until k).map{ k =>
        val dataCentroid = data.filter($"prediction" === k)
          .select("features")
          .collect()
          .map {
            // Get the feature vectors in dense format
            case Row(v: Vector) => v.toDense
          }
        val s = distanceAllCluster(centroids(k), dataCentroid)
        if (s.length > 0)
          s.sum / s.length
        else
          s.sum // Sum will be 0 if no element in cluster
      }
      if (score.nonEmpty)
        score.sum / score.length
      else
        score.sum
    }

    /**
      * Get the maximum value of each centroid
      *
      * @param centroids Array containing all the centroids
      * @param data DataFrame containing the data points
      * @param k The number of cluster
      * @return A Map with k as the key and its maximum value as value
      */
    def maxByCentroid(centroids: Array[Vector], data: DataFrame, k: Int): Map[Int, Double] = {
      val max = (0 until k).map{ k =>
        val dataCentroid = data.filter($"prediction" === k)
          .select("features")
          .collect()
          .map {
            // Get the feature vectors in dense format
            case Row(v: Vector) => v.toDense
          }
        val dist = distanceAllCluster(centroids(k), dataCentroid)
        if (dist.isEmpty) {
          (k, 0.0)
        }
        else
          (k, dist.max)
      }
      max.toMap
    }

    /**
      * Calculate the distance between a point and its centroid
      *
      * This is an udf and must be run on a DataFrame.
      * Usage of currying in order to pass other parameters.
      *
      * The columns of the DataFrame to use: "features" and "prediction"
      * Uses the prediction column to know in which centroid the point belongs.
      *
      * @param centroids Centroids
      * @return
      */
    def calculateDistance(centroids: Array[Vector]) = udf((v: Vector, k: Int) => {
      math.sqrt(centroids(k).toArray.zip(v.toArray)
        .map(p => p._1 - p._2).map(d => d * d).sum)
    })

    /**
      * Check if a point is an anomaly
      *
      * If the score of a point is higher than the maximum of the cluster
      * in which it belongs, it is an anomaly.
      *
      * UDF run on "dist" column and "prediction"
      *
      * @param max Map containing the maximal value of each cluster
      * @return 1 if the paquet is an anomaly, else 0
      */
    def checkAnomaly(max: Map[Int, Double]) = udf((distance: Double, k: Int) => if (distance > max(k)) 1 else 0)

    /**
      * Get all the anomalies of a test set
      *
      * @param pipeline The pipeline used for the preprocessing
      * @param data The test data
      * @param centroids The centroids found on the training data
      * @param max Maximal value of each centroid
      * @return A DataFrame containing the anomalies
      */
    def getAnomalies(pipeline: PipelineModel, data: DataFrame, centroids: Array[Vector], max: Map[Int, Double]) = {
      val predictDF = pipeline.transform(data)

      val distanceDF = predictDF.withColumn("dist", calculateDistance(centroids)(predictDF("features"), predictDF("prediction"))).checkpoint()
      val anomalies = distanceDF.withColumn("anomaly", checkAnomaly(max)(distanceDF("dist"), distanceDF("prediction"))).checkpoint()
      anomalies.filter($"anomaly" > 0)
    }

    /**
      * Anomaly detection on test set
      *
      * Get the maximal value of each cluster, and check for each point
      * if its value is higher than the maximal, if this is the case, this is an anomaly.
      *
      * @param dataDF Data of the training
      * @param pipelineModel Pipeline model used with the training
      * @param k Number of clusters
      * @return A DataFrame containing the anomalies
      */
    def anomalyDectection(dataDF: DataFrame, pipelineModel: PipelineModel, k: Int): DataFrame = {
      // Load the data into the schema created previously
      val dataTestDF = spark.read.format("com.databricks.spark.csv")
        .option("header", "false")
        .option("inferSchema", "true")
        .schema(DataSchema)
        .load(TestPath)

      val testDF = dataTestDF.drop("label")
      testDF.cache()

      // Prediction
      val cluster = pipelineModel.transform(dataDF)

      val kmeansModel = pipelineModel.stages.last.asInstanceOf[KMeansModel]

      // Get the centroids
      val centroids = kmeansModel.clusterCenters

      // Get the maximal distance for each cluster (on the training data)
      val max = this.maxByCentroid(centroids, cluster, k)

      // Detect anomalies on the test data
      val anomalies = getAnomalies(pipelineModel, testDF, centroids, max)
      testDF.unpersist()
      anomalies
    }

    /**
      * Write the result of a run into a file
      *
      * Filename is create dynamically with the current date and the algorithm used.
      *
      * @param score Score already calculated
      * @param startTime Start time of the computation
      * @param technique String with the name of the algorithm/preprocessing used
      */
    def write2file(score: Double, startTime: Long, technique: String): Unit = {
      val format = new SimpleDateFormat("yyyyMMddHHmm")
      val pw = new PrintWriter(new File("results" + format.format(Calendar.getInstance().getTime) +
        "_" + technique.replaceAll(" ", "_") + ".txt"))
      try {
        println(technique)
        pw.write(s"$technique\n")
        println(s"Score=$score")
        pw.write(s"Score=$score\n")
        val duration = (System.nanoTime - startTime) / 1e9d
        println(s"Duration=$duration")
        pw.write(s"Duration=$duration\n")
      } finally {
        pw.close()
      }
    }

    /**
      * K-means with only numerical features, without normalization
      *
      * @param k Number of cluster
      */
    def kmeansSimple(k: Int): Unit = {
      println(s"Running kmeansSimple ($k)")
      val startTime = System.nanoTime()
      // Remove the label column
      val dataDF = this.data.drop("label")
      dataDF.cache()
      val numericalColumns = dataDF.columns.diff(CategoricalColumns)

      // Creation of vector with features
      val assembler = new VectorAssembler()
        .setInputCols(numericalColumns)
        .setOutputCol("features")

      val kmeans = new KMeans()
        .setK(k)
        .setFeaturesCol("features")
        .setPredictionCol("prediction")
        .setSeed(1L)

      val pipeline = new Pipeline()
        .setStages(Array(assembler, kmeans))

      val pipelineModel = pipeline.fit(dataDF)

      val kmeansModel = pipelineModel.stages.last.asInstanceOf[KMeansModel]

      // Prediction
      val cluster = pipelineModel.transform(dataDF)

      // Get the centroids
      val centroids = kmeansModel.clusterCenters

      // Calculate the score
      val score = this.clusteringScore(centroids, cluster, k)

      this.write2file(score, startTime, "K-means (" + k + ") simple")

      // Anomaly detection
      val anomalies = this.anomalyDectection(dataDF, pipelineModel, k)
      // Save results to json file
      val format = new SimpleDateFormat("yyyyMMddHHmm")
      Thread.sleep(1000)
      anomalies.write.json("anomalies_" + format.format(Calendar.getInstance().getTime) + "_" + k + ".json")
      dataDF.unpersist()
    }

    /**
      * K-means using categorical features, without normalization
      *
      * Categorical features are encoded using the One-Hot encoder.
      *
      * @param k Number of cluster
      */
    def kmeansOneHotEncoder(k: Int): Unit = {
      println(s"Running kmeansOneHotEncoder ($k)")
      val startTime = System.nanoTime()
      // Remove the label column
      val dataDF = this.data.drop("label")
      dataDF.cache()

      // Indexing categorical columns
      val indexer: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new StringIndexer()
          .setInputCol(c)
          .setOutputCol(s"${c}_index")
      ).toArray

      // Encoding previously indexed columns
      val encoder: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new OneHotEncoder()
          .setInputCol(s"${c}_index")
          .setOutputCol(s"${c}_vec")
          .setDropLast(false)
      ).toArray

      // Creation of list of columns for vector assembler (with only numerical columns)
      val assemblerColumns = (Set(dataDF.columns: _*) -- CategoricalColumns ++ CategoricalColumns.map(c => s"${c}_vec")).toArray

      // Creation of vector with features
      val assembler = new VectorAssembler()
        .setInputCols(assemblerColumns)
        .setOutputCol("features")

      val kmeans = new KMeans()
        .setK(k)
        .setFeaturesCol("features")
        .setPredictionCol("prediction")
        .setSeed(1L)

      val pipeline = new Pipeline()
        .setStages(indexer ++ encoder ++ Array(assembler, kmeans))

      val pipelineModel = pipeline.fit(dataDF)

      val kmeansModel = pipelineModel.stages.last.asInstanceOf[KMeansModel]

      // Prediction
      val cluster = pipelineModel.transform(dataDF)
      dataDF.unpersist()

      // Get the centroids
      val centroids = kmeansModel.clusterCenters

      // Calculate the score
      val score = this.clusteringScore(centroids, cluster, k)

      this.write2file(score, startTime, "K-means (" + k + ") with one-hot encoder")
    }

    /**
      * K-means using categorical features, with normalization
      *
      * Categorical features are encoded using the One-hot encoder.
      * One-hot encoder will map a column of label indices to a column of binary vectors.
      * Normalization is done using the standard deviation
      *
      * @param k Number of cluster
      */
    def kmeansOneHotEncoderWithNormalization(k: Int): Unit = {
      println(s"Running kmeansOneHotEncoderWithNormalization ($k)")
      val startTime = System.nanoTime()
      // Remove the label column
      val dataDF = this.data.drop("label")
      dataDF.cache()

      // Indexing categorical columns
      val indexer: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new StringIndexer()
          .setInputCol(c)
          .setOutputCol(s"${c}_index")
      ).toArray

      // Encoding previously indexed columns
      val encoder: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new OneHotEncoder()
          .setInputCol(s"${c}_index")
          .setOutputCol(s"${c}_vec")
          .setDropLast(false)
      ).toArray

      // Creation of list of columns for vector assembler (with only numerical columns)
      val assemblerColumns = (Set(dataDF.columns: _*) -- CategoricalColumns ++ CategoricalColumns.map(c => s"${c}_vec")).toArray

      // Creation of vector with features
      val assembler = new VectorAssembler()
        .setInputCols(assemblerColumns)
        .setOutputCol("featuresVector")

      // Normalization using standard deviation
      val scaler = new StandardScaler()
        .setInputCol("featuresVector")
        .setOutputCol("features")
        .setWithStd(true)
        .setWithMean(false)

      val kmeans = new KMeans()
        .setK(k)
        .setFeaturesCol("features")
        .setPredictionCol("prediction")
        .setSeed(1L)

      val pipeline = new Pipeline()
        .setStages(indexer ++ encoder ++ Array(assembler, scaler, kmeans))

      val pipelineModel = pipeline.fit(dataDF)

      // Prediction
      val cluster = pipelineModel.transform(dataDF)
      dataDF.unpersist()

      val kmeansModel = pipelineModel.stages.last.asInstanceOf[KMeansModel]

      // Get the centroids
      val centroids = kmeansModel.clusterCenters

      // Calculate the score
      val score = this.clusteringScore(centroids, cluster, k)

      this.write2file(score, startTime, "K-means (" + k + ") with one-hot encoder with normalization")
    }

    /**
      * Bisecting K-means using categorical features, with normalization
      *
      * With the Bisecting K-means, al observations start in one cluster
      * and split are performed recursively in a "top-down" approach.
      *
      * Categorical features are encoded using the One-hot encoder.
      * One-hot encoder will map a column of label indices to a column of binary vectors.
      * Normalization is done using the standard deviation
      *
      * @param k Number of cluster
      */
    def bisectingKmeansOneHotEncoderWithNormalization(k: Int): Unit = {
      println(s"Running bisectingKmeansOneHotEncoderWithNormalization ($k)")
      val startTime = System.nanoTime()
      // Remove the label column
      val dataDF = this.data.drop("label")
      dataDF.cache()

      // Indexing categorical columns
      val indexer: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new StringIndexer()
          .setInputCol(c)
          .setOutputCol(s"${c}_index")
      ).toArray

      // Encoding previously indexed columns
      val encoder: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new OneHotEncoder()
          .setInputCol(s"${c}_index")
          .setOutputCol(s"${c}_vec")
          .setDropLast(false)
      ).toArray

      // Creation of list of columns for vector assembler (with only numerical columns)
      val assemblerColumns = (Set(dataDF.columns: _*) -- CategoricalColumns ++ CategoricalColumns.map(c => s"${c}_vec")).toArray

      // Creation of vector with features
      val assembler = new VectorAssembler()
        .setInputCols(assemblerColumns)
        .setOutputCol("featuresVector")

      // Normalization using standard deviation
      val scaler = new StandardScaler()
        .setInputCol("featuresVector")
        .setOutputCol("features")
        .setWithStd(true)
        .setWithMean(false)

      val kmeans = new BisectingKMeans()
        .setK(k)
        .setFeaturesCol("features")
        .setPredictionCol("prediction")
        .setSeed(1L)

      val pipeline = new Pipeline()
        .setStages(indexer ++ encoder ++ Array(assembler, scaler, kmeans))

      val pipelineModel = pipeline.fit(dataDF)

      // Prediction
      val cluster = pipelineModel.transform(dataDF)
      dataDF.unpersist()

      val kmeansModel = pipelineModel.stages.last.asInstanceOf[BisectingKMeansModel]

      // Get the centroids
      val centroids = kmeansModel.clusterCenters

      // Calculate the score
      val score = this.clusteringScore(centroids, cluster, k)

      this.write2file(score, startTime, "Bisecting K-means (" + k + ") with one-hot encoder with normalization")
    }

    /**
      * Gaussian Mixture Model
      *
      * Categorical features are encoded using the One-hot encoder.
      * One-hot encoder will map a column of label indices to a column of binary vectors.
      * Normalization is done using the standard deviation
      *
      * GMM uses a quadratic algorithm and in consequence takes really long to perform.
      * This algorithm will only be used on 1% of the dataset.
      *
      * @param k Number of cluster
      */
    def gaussianMixtureOneHotEncoderWithNormalization(k: Int): Unit = {
      println(s"Running gaussianMixtureOneHotEncoderWithNormalization ($k)")
      val startTime = System.nanoTime()
      // Remove the label column
      val dataDF = this.data.drop("label")
      dataDF.cache()

      // Indexing categorical columns
      val indexer: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new StringIndexer()
          .setInputCol(c)
          .setOutputCol(s"${c}_index")
      ).toArray

      // Encoding previously indexed columns
      val encoder: Array[org.apache.spark.ml.PipelineStage] = CategoricalColumns.map(
        c => new OneHotEncoder()
          .setInputCol(s"${c}_index")
          .setOutputCol(s"${c}_vec")
          .setDropLast(false)
      ).toArray

      // Creation of list of columns for vector assembler (with only numerical columns)
      val assemblerColumns = (Set(dataDF.columns: _*) -- CategoricalColumns ++ CategoricalColumns.map(c => s"${c}_vec")).toArray

      // Creation of vector with features
      val assembler = new VectorAssembler()
        .setInputCols(assemblerColumns)
        .setOutputCol("featuresVector")

      // Normalization using standard deviation
      val scaler = new StandardScaler()
        .setInputCol("featuresVector")
        .setOutputCol("features")
        .setWithStd(true)
        .setWithMean(false)

      val gaussianMixture = new GaussianMixture()
        .setK(k)
        .setFeaturesCol("features")
        .setPredictionCol("prediction")
        .setSeed(1L)

      val pipeline = new Pipeline()
        .setStages(indexer ++ encoder ++ Array(assembler, scaler, gaussianMixture))

      val pipelineModel = pipeline.fit(dataDF)

      val gmm = pipelineModel.stages.last.asInstanceOf[GaussianMixtureModel]

      // Prediction
      val cluster = pipelineModel.transform(dataDF)
      dataDF.unpersist()

      // Get the centroids
      val centroids = (0 until k).map(i => gmm.gaussians(i).mean).toArray

      // Calculate the score
      val score = this.clusteringScore(centroids, cluster, k)

      this.write2file(score, startTime, "GaussianMixture (" + k + ") with one-hot encoder with normalization")
    }
  }
}
