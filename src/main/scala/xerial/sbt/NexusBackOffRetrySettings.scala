package xerial.sbt

case class NexusBackOffRetrySettings(initialWaitSeq: Int = 5, intervalSeq: Int = 3, maxRetries: Int = 10)
