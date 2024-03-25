package xerial.sbt.sonatype.utils

import xerial.sbt.sonatype.SonatypeException

private[sbt] object Extensions {
  implicit class EitherOps[A, B](either: Either[A, B]) {
    def leftMap[C](func: A => C): Either[C, B] = either match {
      case Left(left)   => Left(func(left))
      case Right(right) => Right(right)
    }
  }

  implicit class EitherSonatypeExceptionOps[A](either: Either[SonatypeException, A]) {
    def getOrError: A = either.fold(ex => throw ex, identity)
  }
}
