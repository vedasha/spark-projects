package main.scala

import org.apache.spark.sql.SparkSession
import org.apache.hadoop.fs.{ FileSystem, Path }
import org.joda.time.DateTime
import org.apache.spark.sql.functions._
import org.apache.spark.sql.SaveMode
import scala.collection.Map

/**
  Job Summary: 
 * The goal of this job is to create an audiencie based on Points Of Interests (POIs). The method takes as input a time frame (be default, december 2018) and a dataset containing the POIs. This dataset should be already formatted in three columns segment|latitude|longitude (without the index) and with the latitude and longitude with point (".") as delimiter. 
   * The method filters the safegraph data by country, and creates a geocode for both the POIs and the safegraph data. This geocode is used to match both datasets by performing a SQL join. The resulting rows will contain a user id, device type type, user latitude and longitude and POI id, latitude and longitude. Then the vincenty formula is used to calculate distance between the pairs of latitude and longitude.
   The method then proceeds to filter the users by a desired minimum distance returning a final dataset with user id and device type.
   The current method will provide the basis for future more customizable geolocation jobs. 
     */
object POICrossDevicerJson {
   
 
/**
This method reads the safegraph data, selects the columns "ad_id" (device id), "id_type" (user id), "latitude", "longitude", creates a geocode for each row and future spatial operations and finally removes duplicates users that were detected in the same location (i.e. the same user in different lat long coordinates will be conserved, but the same user in same lat long coordinates will be dropped).
   @param spark: Spark session that will be used to load the data.
   @param nDays: parameter to define the number of days. Currently not used, hardcoded to the whole month of december 2018. 
   @param country: country from with to filter the data, it is currently hardcoded to Mexico
   @return df_safegraph: dataframe created with the safegraph data, filtered by the desired country, extracting the columns user id, device id, latitude and longitude removing duplicate users that have repeated locations and with added geocode.
 */
  def get_variables(spark: SparkSession, path_geo_json:String) : Map [String,String] =  {




    val file = "hdfs://rely-hdfs/datascience/geo/geo_json/%s.json".format(path_geo_json)
    val df = spark.sqlContext.read.json(file)

    val max_radius = df.select(col("max_radius")).collect()(0)(0).toString
    val country = df.select(col("country")).collect()(0)(0).toString
    val poi_output_file = df.select(col("output_file")).collect()(0)(0).toString
    val path_to_pois = df.select(col("path_to_pois")).collect()(0)(0).toString
    val crossdevice = df.select(col("crossdevice")).collect()(0)(0).toString
    val nDays = df.select(col("nDays")).collect()(0)(0).toString
    val since = df.select(col("since")).collect()(0)(0).toString

    val value_dictionary: Map [String, String] = Map(
      "max_radius" -> max_radius , 
      "country" -> country, 
      "poi_output_file" -> poi_output_file, 
      "path_to_pois" -> path_to_pois, 
      "crossdevice" -> crossdevice , 
      "nDays" -> nDays,
      "since" -> since)

      value_dictionary 

    //println(file,max_radius,country,poi_output_file,path_to_pois,crossdevice,nDays,"------------------aag87ytg-------------------------")

  }

  def get_safegraph_data(spark: SparkSession, value_dictionary: Map[String,String]) = {
    //loading user files with geolocation, added drop duplicates to remove users who are detected in the same location
    // Here we load the data, eliminate the duplicates so that the following computations are faster, and select a subset of the columns
    // Also we generate a new column call 'geocode' that will be used for the join
   
    // First we obtain the configuration to be allowed to watch if a file exists or not
    val conf = spark.sparkContext.hadoopConfiguration
    val fs = FileSystem.get(conf)

    // Get the days to be loaded
    val format = "yyyy/MM/dd"
    val end   = DateTime.now.minusDays(value_dictionary("since").toInt)
    val days = (0 until value_dictionary("nDays").toInt).map(end.minusDays(_)).map(_.toString(format))
    
    // Now we obtain the list of hdfs folders to be read
    val path = "/data/geo/safegraph/"

    // Now we obtain the list of hdfs folders to be read

     val hdfs_files = days.map(day => path+"%s/".format(day))
                            .filter(path => fs.exists(new org.apache.hadoop.fs.Path(path))).map(day => day+"*.gz")

    val df_safegraph = spark.read.option("header", "true").csv(hdfs_files:_*)
                                  .dropDuplicates("ad_id","latitude","longitude")
                                  .filter("country = '%s'".format(value_dictionary("country")))
                                  .select("ad_id", "id_type", "latitude", "longitude","utc_timestamp")
                                  .withColumnRenamed("latitude", "latitude_user")
                                  .withColumnRenamed("longitude", "longitude_user")
                                  .withColumn("geocode", ((abs(col("latitude_user").cast("float"))*10).cast("int")*10000)+(abs(col("longitude_user").cast("float")*100).cast("int")))

    df_safegraph
  }

/**
  This method reads the user provided POI dataset, adds a geocode for each POI and renames the columns. The file provided must be correctly formatted as described below.
   @param spark: Spark session that will be used to load the data.
   @param file_name: path of the dataset containing the POIs. Must be correctly formated as described (name|latitude|longitude (without the index), with the latitude and longitude with point (".") as decimal delimiter.)
   @param return df_pois_final: dataframe created from the one provided by the user containing the POIS: contains the geocode and renamed columns.   
     */

  def get_POI_coordinates(spark: SparkSession,value_dictionary: Map[String,String]) = {
   
    // Loading POIs. The format of the file is Name, Latitude, Longitude
    val df_pois = spark.read.option("header", "true").option("delimiter", ",").csv(value_dictionary("path_to_pois"))

    //creating geocodes,assigning radius and renaming columns
    val df_pois_parsed = df_pois.withColumn("geocode", ((abs(col("latitude").cast("float"))*10).cast("int")*10000)+(abs(col("longitude").cast("float")*100).cast("int")))
                                .withColumnRenamed("latitude","latitude_poi")
                                .withColumnRenamed("longitude","longitude_poi")
                                
    if (df_pois_parsed.columns.contains("radius")) {
             val df_pois_final = df_pois_parsed

            df_pois_final}
    else {
            val df_pois_final = df_pois_parsed.
                                withColumn("radius", lit(value_dictionary("max_radius").toInt))
       df_pois_final}                             
    // Here we rename the columns
    //val columnsRenamed_poi = Seq("name", "latitude_poi", "longitude_poi", "radius", "geocode")

    //renaming columns based on list
    //val df_pois_final = df_pois_parsed.toDF(columnsRenamed_poi: _*)

          
  }

/**
  This method performs the vincenty distance calculation between the POIs and the Safegraph data for the selected country and month.
  To do this, we perform a join between both dataframes using the geocode. The resulting dataframe has a row where both the latitude and longitude of the user and the poi are present. Then using the vincenty formula, a distance is obtained. Finaly, we filter it by a minium distance.
 
   @param spark: Spark session that will be used to load the data.
   @param file_name: path of the dataset containing the POIs. Must be correctly formated as described (name|latitude|longitude (without the index), with the latitude and longitude with point (".") as decimal delimiter.)
   @param return df_pois_final: dataframe created from the one provided by the user containing the POIS: contains the geocode and renamed columns.   
     */

     // fuertemente tipado
  def match_POI(spark: SparkSession, value_dictionary: Map [String,String]) = {
  
    val df_users = get_safegraph_data(spark, value_dictionary) ///////
    val df_pois_final = get_POI_coordinates(spark, value_dictionary)

    //joining datasets by geocode (added broadcast to force..broadcasting)
    val joint = df_users.join(broadcast(df_pois_final),Seq("geocode")).
        withColumn("longitude_poi", round(col("longitude_poi").cast("float"),4)).
        withColumn("latitude_poi", round(col("latitude_poi").cast("float"),4)).
        withColumn("longitude_user", round(col("longitude_user").cast("float"),4)).
        withColumn("latitude_user", round(col("latitude_user").cast("float"),4))


    //using vincenty formula to calculate distance between user/device location and the POI
    //currently the distance is hardcoded to 50 m. 
    joint.createOrReplaceTempView("joint")
    val query = """SELECT *
                FROM (
                  SELECT *,((1000*111.045)*DEGREES(ACOS(COS(RADIANS(latitude_user)) * COS(RADIANS(latitude_poi)) *
                  COS(RADIANS(longitude_user) - RADIANS(longitude_poi)) +
                  SIN(RADIANS(latitude_user)) * SIN(RADIANS(latitude_poi))))) as distance
                  FROM joint 
                )
                WHERE distance < radius"""

    
    
    //storing result
    val sqlDF = spark.sql(query)
    
    //we want information about the process              
    println(sqlDF.explain(extended = true))         
    
       
    val filtered = 
    sqlDF.write.format("csv").option("sep", "\t").mode(SaveMode.Overwrite).save("/datascience/geo/%s".format(value_dictionary("poi_output_file")))
  }

//hasta aca es el poi matcher, ahora agrego el crossdevicer

def cross_device(spark: SparkSession, value_dictionary: Map [String,String]) = {
    

     if(value_dictionary("crossdevice")=="true") {
    // First we get the audience. Also, we transform the device id to be upper case.
    //val path_audience = "/datascience/audiences/output/%s".format(audience_name)
    //val audience_name = value_dictionary("poi_output_file").split("/").last
    val audience = spark.read.format("csv").option("sep", "\t").load("/datascience/geo/%s".format(value_dictionary("poi_output_file")))
                                                              .withColumnRenamed("_c1", "device_id")
                                                              .withColumn("device_id", upper(col("device_id")))
                                                              .select("device_id").distinct()

//hay que arreglar el filtro del index porque no se guarda nada después de hacer el crossdevice, que igual tarda mucho
//"index_type IN ('and', 'ios') AND device_type IN ('coo')"
    
    // Get DrawBridge Index. Here we transform the device id to upper case too.
    val db_data = spark.read.format("parquet").load("/datascience/crossdevice/double_index")
                                             .filter("index_type IN ('and', 'ios') AND device_type = 'coo'")
                                              .withColumn("index", upper(col("index")))
                                              .select("index", "device", "device_type")
    

    // Here we do the cross-device per se.
    val cross_deviced = db_data.join(audience, db_data.col("index")===audience.col("device_id"))
                               //.select("index", "device", "device_type")
    
    //we want information about the process              
    println(cross_deviced.explain(extended = true))              
    
    // Finally, we store the result obtained.
    val output_path = "/datascience/audiences/crossdeviced/%s_xd".format(value_dictionary("poi_output_file"))
    cross_deviced.write.format("csv").mode(SaveMode.Overwrite).save(output_path)
  }
}
  
    type OptionMap = Map[Symbol, Any]

  
  /**
   * This method parses the parameters sent.
   */
  def nextOption(map: OptionMap, list: List[String]): OptionMap = {
    def isSwitch(s: String) = (s(0) == '-')
    list match {
      case Nil => map
      case "--path_geo_json" :: value :: tail =>
        nextOption(map ++ Map('path_geo_json -> value.toString), tail) 
        /*
      case "--nDays" :: value :: tail =>
        nextOption(map ++ Map('nDays -> value.toInt), tail)
      case "--country" :: value :: tail =>
        nextOption(map ++ Map('country -> value.toString), tail)
      case "--poi_file" :: value :: tail =>
        nextOption(map ++ Map('poi_file -> value.toString), tail)
      case "--max_radius" :: value :: tail =>
        nextOption(map ++ Map('max_radius -> value.toInt), tail)
      case "--output" :: value :: tail =>
        nextOption(map ++ Map('output -> value.toString), tail)
      case "--xd" :: value :: tail =>
        nextOption(map ++ Map('xd -> value.toString), tail) 
        */
        }
  }
  

  
  def main(args: Array[String]) {
    // Parse the parameters
    val options = nextOption(Map(), args.toList)
    val path_geo_json = if (options.contains('path_geo_json)) options('path_geo_json).toString else ""
    
   // val safegraph_days = if (options.contains('nDays)) options('nDays).toString.toInt else 30
   // val country = if (options.contains('country)) options('country).toString else "mexico"
   // val POI_file_name = if (options.contains('poi_file)) options('poi_file).toString else ""
   // val max_radius = if (options.contains('max_radius)) options('max_radius).toString.toInt else 100
   // val poi_output_file = if (options.contains('output)) options('output).toString else ""
   // val xd = if (options.contains('xd)) options('xd).toString else ""
   
    // Start Spark Session
    val spark = SparkSession.builder.appName("audience generator by keywords").getOrCreate()

    // chequear que el POI_file_name este especificado y el poi_output_file tambien

    //val POI_file_name = "hdfs://rely-hdfs/datascience/geo/poi_test_2.csv"
    //val poi_output_file = "/datascience/geo/MX/specific_POIs"

    val value_dictionary = get_variables(spark, path_geo_json)

    match_POI(spark, value_dictionary)
      // Finally, we perform the cross-device
  
    cross_device(spark, value_dictionary)
    
   
   
  
  }
}
