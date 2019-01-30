package main.scala
import org.apache.spark.sql.{SparkSession, Row, SaveMode}
import org.apache.spark.sql.functions._
import org.joda.time.{Days, DateTime}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.{SaveMode, DataFrame}
import org.apache.spark.ml.attribute.Attribute
import org.apache.spark.ml.feature.{IndexToString, StringIndexer}
//import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler}
import org.apache.spark.sql.types.{
  DoubleType,
  StringType,
  StructField,
  StructType
}
import org.apache.spark.sql.{Encoders, SparkSession}
import org.joda.time.Days
import org.joda.time.DateTime
import org.apache.hadoop.conf.Configuration
import org.apache.spark.ml.classification.{RandomForestClassificationModel,RandomForestClassifier}
import org.apache.spark.ml.classification.MultilayerPerceptronClassifier
import org.apache.spark.ml.classification.{GBTClassificationModel, GBTClassifier}


object TrainModel {
  def getTrainingSet(spark: SparkSession) {
    //val df = spark.read.parquet(
    //  "/datascience/data_demo/triplets_segments/part-06761-36693c74-c327-43a6-9482-2e83c0ead518-c000.snappy.parquet"
    //)
    val df = spark.read.parquet("/datascience/data_demo/triplets_segments/")

    val gt_male = spark.read
      .format("csv")
      .option("sep", " ")
      .load("/datascience/devicer/processed/ground_truth_male")
      .withColumn("label", lit(1))
      .withColumnRenamed("_c1", "device_id")
      .select("device_id", "label")
    val gt_female = spark.read
      .format("csv")
      .option("sep", " ")
      .load("/datascience/devicer/processed/ground_truth_female")
      .withColumn("label", lit(0))
      .withColumnRenamed("_c1", "device_id")
      .select("device_id", "label")

    val gt = gt_male.unionAll(gt_female)

    /// Hacemos el join y sacamos los segmentos 2 y 3 del dataframe.
    val joint = gt.join(df, Seq("device_id")).filter("feature <> '2' and feature <> '3'")

    joint.write.mode(SaveMode.Overwrite).save("/datascience/data_demo/training_set/")
  }

  def getLabeledPointSet(spark: SparkSession) {
    val data = spark.read.format("parquet").load("/datascience/data_demo/training_set")

    val indexer1 = new StringIndexer().setInputCol("device_id").setOutputCol("deviceIndex")
    val indexed1 = indexer1.fit(data).transform(data)
    val indexer2 = new StringIndexer().setInputCol("feature").setOutputCol("featureIndex")
    val indexed_data = indexer2.fit(indexed1).transform(indexed1)

    val maximo = indexed_data
      .agg(max("featureIndex"))
      .collect()(0)(0)
      .toString
      .toDouble
      .toInt

    // Agrupamos y sumamos los counts por cada feature
    val grouped_indexed_data = indexed_data
      .groupBy("device_id", "label", "featureIndex")
      .agg(sum("count").cast("int").as("count"))
    // Agrupamos nuevamente y nos quedamos con la lista de features para cada device_id
    val grouped_data = grouped_indexed_data
      .groupBy("device_id", "label")
      .agg(
        collect_list("featureIndex").as("features"),
        collect_list("count").as("counts")
      )

    // Esta UDF arma un vector esparso con los features y sus valores de count.
    val udfFeatures = udf(
      (label: Int, features: Seq[Double], counts: Seq[Int], maximo: Int) =>
        Vectors.sparse(
          maximo + 1,
          (features.toList.map(f => f.toInt) zip counts.toList.map(
            f => f.toDouble
          )).toSeq.distinct.sortWith((e1, e2) => e1._1 < e2._1).toSeq
        )
    )

    val df_final = grouped_data.withColumn(
      "features_sparse",
      udfFeatures(col("label"), col("features"), col("counts"), lit(maximo))
    )
    df_final.write
      .mode(SaveMode.Overwrite)
      .save("/datascience/data_demo/labeled_points")
  }

  def train_and_evaluate_model(spark: SparkSession) {
    import spark.implicits._

    val data = spark.read.format("parquet").load("/datascience/data_demo/labeled_points")

    //We'll split the set into training and test data
    val Array(trainingData, testData) = data.randomSplit(Array(0.7, 0.3))

    val labelColumn = "label"

    //We define the assembler to collect the columns into a new column with a single vector - "features sparse"
    val assembler = new VectorAssembler()
      .setInputCols(Array("features_sparse"))
      .setOutputCol("features_sparse")

/** MODELS
    val model = new GBTClassifier()
        .setLabelCol(labelColumn)
        .setFeaturesCol("features_sparse")
        .setPredictionCol("predicted_" + labelColumn)
        .setMaxIter(50)

    
    // specify layers for the neural network:
    // input layer of size 4 (features), two intermediate of size 5 and 4
    // and output of size 2 (classes)
    val layers = Array[Int](5406, 3, 3, 2)

    // create the trainer and set its parameters
    val model = new MultilayerPerceptronClassifier()
                      .setLayers(layers)
                      .setBlockSize(128)
                      .setSeed(1234L)
                      .setMaxIter(100)
**/
    val rf = new RandomForestClassifier()
        .setLabelCol(labelColumn)
        .setFeaturesCol("features_sparse")
        .setPredictionCol("predicted_" + labelColumn)
        .setNumTrees(100)

    //We define the Array with the stages of the pipeline
    val stages = Array(rf)

    //Construct the pipeline
    val pipeline = new Pipeline().setStages(stages)

    //We fit our DataFrame into the pipeline to generate a model
    val model = pipeline.fit(trainingData)

    //We'll make predictions using the model and the test data
    val predictions = model.transform(testData)
    predictions.write
      .mode(SaveMode.Overwrite)
      .save("/datascience/data_demo/predictions_rf")

    // We compute AUC and F1
    val predictionLabelsRDD = predictions
      .select("predicted_label", "label")
      .rdd
      .map(r => (r.getDouble(0), r.getInt(1).toDouble))
    val binMetrics = new BinaryClassificationMetrics(predictionLabelsRDD)

    val auc = binMetrics.areaUnderROC
    val f1 = binMetrics.fMeasureByThreshold

    //This will evaluate the error/deviation of the regression using the Root Mean Squared deviation
    val evaluator = new RegressionEvaluator()
      .setLabelCol(labelColumn)
      .setPredictionCol("predicted_" + labelColumn)
      .setMetricName("rmse")

    //We compute the error using the evaluator
    val error = evaluator.evaluate(predictions)

    // We store the metrics in a json file
    val conf = new Configuration()
    conf.set("fs.defaultFS", "hdfs://rely-hdfs")
    val fs = FileSystem.get(conf)
    val os = fs.create(new Path("/datascience/data_demo/metrics_rf.json"))
    val json_content =
      """{"auc":"%s", "f1":%s, "rmse":%s}""".format(auc, f1, error)
    os.write(json_content.getBytes)
    fs.close()

  }



  def main(args: Array[String]) {
    val spark = SparkSession.builder.appName("Train and evaluate model").getOrCreate()
    
    getTrainingSet(spark)
    getLabeledPointSet(spark)
    train_and_evaluate_model(spark)
  }

}