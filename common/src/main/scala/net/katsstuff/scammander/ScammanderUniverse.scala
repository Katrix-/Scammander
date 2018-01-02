package net.katsstuff.scammander

import scala.util.Try

import net.katsstuff.scammander.misc.{MkHListWitness, RawCmdArg}
import shapeless.labelled.FieldType
import shapeless._

trait ScammanderUniverse[RootSender, RunExtra, TabExtra]
    extends NormalParametersInstances[RootSender, RunExtra, TabExtra]
    with ParameterLabelledDeriver[RootSender, RunExtra, TabExtra] {

  trait UserValidator[A] {

    def validate(sender: RootSender): Either[CmdFailure, A]

    def toSender(a: A): RootSender
  }
  object UserValidator {
    def mkTransformer[A](validator: RootSender => Either[CmdFailure, A])(back: A => RootSender): UserValidator[A] =
      new UserValidator[A] {
        override def validate(sender: RootSender): Either[CmdFailure, A] = validator(sender)
        override def toSender(a: A):               RootSender            = back(a)
      }

    implicit val rootValidator: UserValidator[RootSender] = mkTransformer(Right.apply)(identity)
  }

  sealed trait CmdResult
  object CmdResult {
    def success(count: Int = 1): CmdSuccess = CmdSuccess(count)
    def error(msg: String):      CmdError   = CmdError(msg)
  }
  case class CmdSuccess(count: Int) extends CmdResult
  sealed trait CmdFailure extends CmdResult {
    def msg: String
    def merge(failure: CmdFailure): CmdFailure = MultipleCmdErrors(Seq(this, failure))
  }
  case class CmdError(msg: String)                      extends CmdFailure
  case class CmdSyntaxError(msg: String, position: Int) extends CmdFailure
  case class CmdUsageError(msg: String, position: Int)  extends CmdFailure
  case class MultipleCmdErrors(failures: Seq[CmdFailure]) extends CmdFailure {
    override def merge(failure: CmdFailure): CmdFailure = MultipleCmdErrors(failures :+ failure)
    override def msg:                        String     = failures.take(5).map(_.msg).mkString("\n")
  }

  abstract class Command[Sender, Param](implicit val userValidator: UserValidator[Sender], val par: Parameter[Param]) {

    def run(source: Sender, extra: RunExtra, arg: Param): CmdResult

    def suggestions(source: Sender, extra: TabExtra, strArgs: List[RawCmdArg]): Seq[String] =
      par.suggestions(userValidator.toSender(source), extra, strArgs)._2

    def usage(source: RootSender): String = par.usage(source)
  }
  object Command {
    def simple[Sender, Param](
        runCmd: (Sender, RunExtra, Param) => CmdResult
    )(implicit transformer: UserValidator[Sender], parameter: Parameter[Param]): Command[Sender, Param] =
      new Command[Sender, Param] {
        override def run(source: Sender, extra: RunExtra, arg: Param): CmdResult = runCmd(source, extra, arg)
      }
  }

  trait Parameter[A] {

    def name: String

    def parse(source: RootSender, extra: RunExtra, xs: List[RawCmdArg]): Either[CmdFailure, (List[RawCmdArg], A)]

    def suggestions(source: RootSender, extra: TabExtra, xs: List[RawCmdArg]): (List[RawCmdArg], Seq[String])

    def usage(source: RootSender): String = s"<$name>"
  }
  trait ProxyParameter[A, B] extends Parameter[A] {
    def param: Parameter[B]

    override def name: String = param.name

    override def suggestions(source: RootSender, extra: TabExtra, xs: List[RawCmdArg]): (List[RawCmdArg], Seq[String]) =
      param.suggestions(source, extra, xs)

    override def usage(source: RootSender): String = param.usage(source)
  }

  object Parameter {
    def apply[A](implicit param: Parameter[A]): Parameter[A] = param
  }

  case class Named[S <: String, A](param: Parameter[A])(implicit w: Witness.Aux[S]) extends ProxyParameter[A, A] {
    override def name: String = w.value

    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], A)] =
      param.parse(source, extra, xs)
  }

  case class Choices(name: String, choices: Set[String], sendValid: Boolean = false) extends Parameter[String] {
    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], String)] = {
      if (xs.nonEmpty) {
        val head = xs.head
        if (choices.contains(head.content)) Right((xs.tail, head.content))
        else {
          if (sendValid)
            Left(
              CmdUsageError(s"$head is not a valid parameter.\nValid parameters: ${choices.mkString(", ")}", head.start)
            )
          else Left(CmdUsageError(s"$head is not a valid parameter.", head.start))
        }
      } else Left(CmdSyntaxError("Not enough parameters", -1))
    }

    override def suggestions(
        source: RootSender,
        extra: TabExtra,
        xs: List[RawCmdArg]
    ): (List[RawCmdArg], Seq[String]) = {
      val head = xs.head
      val tail = xs.tail

      if (tail.isEmpty) (Nil, choices.filter(head.content.startsWith).toSeq) else (tail, Nil)
    }
  }

  class ChoicesT[Name <: String, L <: HList, SendValid <: Boolean](
      implicit nameW: Witness.Aux[Name],
      mkHList: MkHListWitness[L],
      toTraversable: ops.hlist.ToTraversable.Aux[L, Set, String],
      sendValidW: Witness.Aux[SendValid]
  ) extends ProxyParameter[String, String] {
    private val choices: Set[String] = toTraversable(mkHList.value)

    override val param: Parameter[String] = Choices(nameW.value, choices, sendValidW.value)

    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], String)] =
      param.parse(source, extra, xs)
  }

  case class NeedPermission[S <: String, A](param: Parameter[A])(implicit w: Witness.Aux[S])
      extends ProxyParameter[A, A] {
    val perm: String = w.value

    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], A)] =
      if (hasSenderPermission(source, perm)) param.parse(source, extra, xs)
      else
        Left(
          CmdUsageError(
            "You do not have the permissions needed to use this parameter",
            xs.headOption.map(_.start).getOrElse(-1)
          )
        )
  }

  def hasSenderPermission(sender: RootSender, permission: String): Boolean

  case class OnlyOne[A](param: Parameter[Seq[A]]) extends ProxyParameter[A, Seq[A]] {

    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], A)] =
      param.parse(source, extra, xs).flatMap {
        case (rest, seq) if seq.size == 1 => Right((rest, seq.head))
        case _                            => Left(CmdUsageError("More than one possible value", xs.headOption.map(_.start).getOrElse(-1)))
      }
  }

  case class RemainingAsString(string: String) {
    override def toString: String = string
    override def hashCode(): Int = string.hashCode
    override def equals(obj: scala.Any): Boolean = string.equals(obj)
  }

  implicit val remainingAsStringParam: Parameter[RemainingAsString] = new Parameter[RemainingAsString] {
    override def name = "strings..."

    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], RemainingAsString)] =
      Right((Nil, RemainingAsString(xs.map(_.content).mkString(" "))))

    override def suggestions(source: RootSender, extra: TabExtra, xs: List[RawCmdArg]): (List[RawCmdArg], Seq[String]) =
      (Nil, Nil)
  }

  class AllOff[A]
  /*TODO: Find way to use Option class instead
    class Optional[A]
    class OptionalWeak[A]
 */
}

trait NormalParametersInstances[RootSender, RunExtra, TabExtra] {
  self: ScammanderUniverse[RootSender, RunExtra, TabExtra] =>
  def primitivePar[A](parName: String, s: String => A): Parameter[A] =
    new Parameter[A] {
      override def name: String = parName

      override def parse(
          source: RootSender,
          extra: RunExtra,
          xs: List[RawCmdArg]
      ): Either[CmdFailure, (List[RawCmdArg], A)] =
        if (xs.nonEmpty)
          Try(s(xs.head.content)).map(xs.tail -> _).toEither.left.map(e => CmdSyntaxError(e.getMessage, xs.head.start))
        else Left(CmdSyntaxError("Not enough parameters", -1))

      override def suggestions(
          source: RootSender,
          extra: TabExtra,
          xs: List[RawCmdArg]
      ): (List[RawCmdArg], Seq[String]) = (xs.tail, Nil)
    }

  def mkSingle[A](
      parName: String,
      parser: String => Either[CmdFailure, A],
      possibleSuggestions: () => Seq[String]
  ): Parameter[A] =
    new Parameter[A] {
      override def name: String = parName

      override def parse(
          source: RootSender,
          extra: RunExtra,
          xs: List[RawCmdArg]
      ): Either[CmdFailure, (List[RawCmdArg], A)] =
        if (xs.nonEmpty) parser(xs.head.content).map(xs.tail -> _)
        else Left(CmdSyntaxError("Not enough parameters", -1))

      override def suggestions(
          source: RootSender,
          extra: TabExtra,
          xs: List[RawCmdArg]
      ): (List[RawCmdArg], Seq[String]) = {
        val head = xs.head
        val tail = xs.tail

        if (tail.isEmpty) (Nil, possibleSuggestions().filter(head.content.startsWith)) else (tail, Nil)
      }
    }

  implicit val bytePar:   Parameter[Byte]    = primitivePar("byte", _.toByte)
  implicit val shortPar:  Parameter[Short]   = primitivePar("short", _.toShort)
  implicit val intPar:    Parameter[Int]     = primitivePar("int", _.toInt)
  implicit val longPar:   Parameter[Long]    = primitivePar("long", _.toLong)
  implicit val floatPar:  Parameter[Float]   = primitivePar("float", _.toFloat)
  implicit val doublePar: Parameter[Double]  = primitivePar("double", _.toDouble)
  implicit val boolPar:   Parameter[Boolean] = primitivePar("boolean", _.toBoolean)
  implicit val strPar:    Parameter[String]  = primitivePar("string", identity)
}

trait ParameterLabelledDeriver[RootSender, RunExtra, TabExtra]
    extends ParameterDeriver[RootSender, RunExtra, TabExtra] {
  self: ScammanderUniverse[RootSender, RunExtra, TabExtra] =>

  implicit def genParam[A, Gen](implicit gen: LabelledGeneric.Aux[A, Gen], genParam: Parameter[Gen]): Parameter[A] =
    new ProxyParameter[A, Gen] {
      override def param: Parameter[Gen] = genParam

      override def parse(
          source: RootSender,
          extra: RunExtra,
          xs: List[RawCmdArg]
      ): Either[CmdFailure, (List[RawCmdArg], A)] =
        genParam.parse(source, extra, xs).map(t => t._1 -> gen.from(t._2))
    }

  implicit def hConsLabelledParam[HK <: Symbol, HV, T <: HList](
      implicit
      hName: Witness.Aux[HK],
      hParam: Lazy[Parameter[HV]],
      tParam: Lazy[Parameter[T]]
  ): Parameter[FieldType[HK, HV] :: T] =
    new Parameter[FieldType[HK, HV] :: T] {
      override def name: String = s"${hName.value.name} ${tParam.value.name}"

      override def parse(
          source: RootSender,
          extra: RunExtra,
          xs: List[RawCmdArg]
      ): Either[CmdFailure, (List[RawCmdArg], ::[FieldType[HK, HV], T])] = {
        for {
          t1 <- hParam.value.parse(source, extra, xs)
          t2 <- tParam.value.parse(source, extra, t1._1)
        } yield (Nil, labelled.field[HK](t1._2) :: t2._2)
      }

      override def suggestions(
          source: RootSender,
          extra: TabExtra,
          xs: List[RawCmdArg]
      ): (List[RawCmdArg], Seq[String]) = {
        val (ys, h)   = hParam.value.suggestions(source, extra, xs)
        val (rest, t) = tParam.value.suggestions(source, extra, ys)

        (rest, h ++ t)
      }
    }

  implicit def cConsLabelledParam[HK <: Symbol, HV, T <: Coproduct](
      implicit
      hName: Witness.Aux[HK],
      hParam: Lazy[Parameter[HV]],
      tParam: Lazy[Parameter[T]]
  ): Parameter[FieldType[HK, HV] :+: T] =
    new Parameter[FieldType[HK, HV] :+: T] {
      override def name: String = s"${hName.value.name}|${tParam.value.name}"

      override def parse(
          source: RootSender,
          extra: RunExtra,
          xs: List[RawCmdArg]
      ): Either[CmdFailure, (List[RawCmdArg], FieldType[HK, HV] :+: T)] = {
        for {
          e1 <- hParam.value.parse(source, extra, xs).map { case (ys, h) => ys -> Inl(labelled.field[HK](h)) }.left
          e2 <- tParam.value.parse(source, extra, xs).map { case (ys, t) => ys -> Inr(t) }.left
        } yield e1.merge(e2)
      }

      override def suggestions(
          source: RootSender,
          extra: TabExtra,
          xs: List[RawCmdArg]
      ): (List[RawCmdArg], Seq[String]) = {
        val (hRest, h) = hParam.value.suggestions(source, extra, xs)
        val (tRest, t) = tParam.value.suggestions(source, extra, xs)

        val rest = if (hRest.size > tRest.size) hRest else tRest
        (rest, h ++ t)
      }
    }
}

trait ParameterDeriver[RootSender, RunExtra, TabExtra] { self: ScammanderUniverse[RootSender, RunExtra, TabExtra] =>
  implicit def hConsParam[H, T <: HList](
      implicit hParam: Lazy[Parameter[H]],
      tParam: Lazy[Parameter[T]]
  ): Parameter[H :: T] =
    new Parameter[H :: T] {
      override def name: String = s"${hParam.value.name} ${tParam.value.name}"

      override def parse(
          source: RootSender,
          extra: RunExtra,
          xs: List[RawCmdArg]
      ): Either[CmdFailure, (List[RawCmdArg], ::[H, T])] = {
        for {
          t1 <- hParam.value.parse(source, extra, xs)
          t2 <- tParam.value.parse(source, extra, t1._1)
        } yield (Nil, t1._2 :: t2._2)
      }

      override def suggestions(
          source: RootSender,
          extra: TabExtra,
          xs: List[RawCmdArg]
      ): (List[RawCmdArg], Seq[String]) = {
        val (ys, h)   = hParam.value.suggestions(source, extra, xs)
        val (rest, t) = tParam.value.suggestions(source, extra, ys)

        (rest, h ++ t)
      }
    }

  implicit val hNilParam: Parameter[HNil] = new Parameter[HNil] {
    override def name: String = ""

    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], HNil)] =
      Right((xs, HNil))

    override def suggestions(source: RootSender, extra: TabExtra, xs: List[RawCmdArg]): (List[RawCmdArg], Seq[String]) =
      (xs, Nil)
  }

  implicit def cConsParam[H, T <: Coproduct](
      implicit hParam: Lazy[Parameter[H]],
      tParam: Lazy[Parameter[T]]
  ): Parameter[H :+: T] =
    new Parameter[H :+: T] {
      override def name: String = s"${hParam.value.name}|${tParam.value.name}"

      override def parse(
          source: RootSender,
          extra: RunExtra,
          xs: List[RawCmdArg]
      ): Either[CmdFailure, (List[RawCmdArg], :+:[H, T])] = {
        for {
          e1 <- hParam.value.parse(source, extra, xs).map { case (ys, h) => ys -> Inl(h) }.left
          e2 <- tParam.value.parse(source, extra, xs).map { case (ys, t) => ys -> Inr(t) }.left
        } yield e1.merge(e2)
      }

      override def suggestions(
          source: RootSender,
          extra: TabExtra,
          xs: List[RawCmdArg]
      ): (List[RawCmdArg], Seq[String]) = {
        val (hRest, h) = hParam.value.suggestions(source, extra, xs)
        val (tRest, t) = tParam.value.suggestions(source, extra, xs)

        val rest = if (hRest.size > tRest.size) hRest else tRest
        (rest, h ++ t)
      }
    }

  implicit val cNilParam: Parameter[CNil] = new Parameter[CNil] {
    override def name: String = ""

    override def parse(
        source: RootSender,
        extra: RunExtra,
        xs: List[RawCmdArg]
    ): Either[CmdFailure, (List[RawCmdArg], CNil)] =
      sys.error("CNil")

    override def suggestions(source: RootSender, extra: TabExtra, xs: List[RawCmdArg]): (List[RawCmdArg], Seq[String]) =
      (xs, Nil)
  }
}
