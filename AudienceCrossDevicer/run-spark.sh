spark-submit\
  --class "main.scala.AudienceCrossDevicer"\
  --master yarn\
  --deploy-mode cluster\
  --driver-memory 8g\
  --executor-memory 8g\
  --num-executors 10\
  --executor-cores 4\
  --queue default\
  --conf spark.yarn.maxAppAttempts=1\
  /home/rely/spark-projects/AudienceCrossDevicer/target/scala-2.11/audience-cross-devicer_2.11-1.0.jar --filter "index_type IN ('coo')" --sep "," --column "_c0" /datascience/audiences/custom_audiences/device_id_vuse
