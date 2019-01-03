package main.scala
import org.apache.spark.sql.{SparkSession, Row, SaveMode}
import org.apache.spark.sql.functions.{explode,desc,lit,size,concat,col,concat_ws,collect_list,udf,broadcast,sha2}
import org.joda.time.{Days,DateTime}
import org.apache.hadoop.fs.Path

object generateSample {
    def generate_sample(spark:SparkSession){

        val organic_xd = spark.read.format("csv")
                                .load("/datascience/data_publicis/organic_xd")
                                .withColumnRenamed("_c0","device_id")
                                .withColumnRenamed("_c1","general_segments")
                                .withColumnRenamed("_c2","geo_segments")
                                .withColumnRenamed("_c3","android")
                                .withColumnRenamed("_c4","ios")
        
        val modeled = spark.read.format("csv").option("sep", "\t")
                                .load("/datascience/data_publicis/modeled")
                                .withColumnRenamed("_c0","device_id")
                                .withColumnRenamed("_c1","modeled_segments")
        
        val sample = organic_xd.join(modeled,Seq("device_id"),"left_outer")
                                .withColumn("device_id",sha2(col("device_id"), 256))
        
        sample.write.format("csv").option("sep", "\t")
                            .option("header",true)
                            .mode(SaveMode.Overwrite)
                            .save("/datascience/data_publicis/sample_publicis")

    }
    def main(args: Array[String]) {
        /// Configuracion spark
        val spark = SparkSession.builder.appName("Generate Sample").getOrCreate()
        
        generate_sample(spark)
    }
}