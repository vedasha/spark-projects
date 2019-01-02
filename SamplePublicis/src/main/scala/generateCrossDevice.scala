package main.scala
import org.apache.spark.sql.{SparkSession, Row, SaveMode}
import org.apache.spark.sql.functions.{explode,desc,lit,size,concat,col,concat_ws,collect_list,udf,broadcast}
import org.joda.time.{Days,DateTime}

object generateCrossDevice {
    def generate_organic_xd(spark:SparkSession):Unit{
      val df = spark.read.format("parquet").load("/datascience/crossdevice")
                                          .filter("index_type = 'c' and device_type in ('a','i')")
                                          .withColumn("device",concat($"device_type",$"device"))
                                          .groupBy("index")
                                          .agg(collect_list("device"))
                                          
      val udfAndroid = udf((segments: Seq[String]) => segments.filter(segment => segment.charAt(0) == 'a'))
      val udfIos = udf((segments: Seq[String]) => segments.filter(segment => segment.charAt(0) == 'i'))

      val index_xd = df.withColumn("android",udfAndroid($"collect_list(device)"))
                        .withColumn("ios",udfIos($"collect_list(device)"))
                        .withColumnRenamed("index","device_id")

      val organic = spark.read.format("csv").option("sep", "\t")
                              .load("/datascience/data_publicis/organic")
                              .withColumnRenamed("_c0","device_id")
                              .withColumnRenamed("_c1","general_segments")
                              .withColumnRenamed("_c2","geo_segments")

      val joint = organic.join(index_xd,Seq("device_id"),"left_outer")

      joint.write.format("csv")
                  .mode(SaveMode.Overwrite)
                  .save("/datascience/data_publicis/organic_xd")
    }
    def main(args: Array[String]) {
        /// Configuracion spark
        val spark = SparkSession.builder.appName("Generate Cross device").getOrCreate()
        ////////////////////// ACTUAL EXECUTION ////////////////////////
        generate_organic_xd(spark)
    }
}