package upickle

import java.io.ByteArrayOutputStream

import language.experimental.macros
import language.higherKinds
import upickle.core._
import scala.reflect.ClassTag
import ujson.IndexedValue

/**
 * An instance of the upickle API. There's a default instance at
 * `upickle.default`, but you can also implement it yourself to customize
 * its behavior. Override the `annotate` methods to control how a sealed
 * trait instance is tagged during reading and writing.
 */
trait Api
    extends upickle.core.Types
    with implicits.Readers
    with implicits.Writers
    with implicits.CaseClassReadWriters
    with WebJson
    with JsReadWriters
    with MsgReadWriters
    with Annotator{

  /**
    * Reads the given MessagePack input into a Scala value
    */
  def readBinary[T: Reader](s: upack.Readable, trace: Boolean = false): T = {
    TraceVisitor.withTrace(trace, reader[T])(s.transform(_))
  }

  /**
    * Reads the given JSON input into a Scala value
    */
  def read[T: Reader](s: ujson.Readable, trace: Boolean = false): T = {
    TraceVisitor.withTrace(trace, reader[T])(s.transform(_))
  }

  def reader[T: Reader] = implicitly[Reader[T]]

  /**
    * Write the given Scala value as a JSON string
    */
  def write[T: Writer](t: T,
                       indent: Int = -1,
                       escapeUnicode: Boolean = false): String = {
    transform(t).to(ujson.StringRenderer(indent, escapeUnicode)).toString
  }
  /**
    * Write the given Scala value as a MessagePack binary
    */
  def writeBinary[T: Writer](t: T): Array[Byte] = {
    transform(t).to(new upack.MsgPackWriter(new ByteArrayOutputStream())).toByteArray
  }

  /**
    * Write the given Scala value as a JSON struct
    */
  def writeJs[T: Writer](t: T): ujson.Value = transform(t).to[ujson.Value]

  /**
    * Write the given Scala value as a MessagePack struct
    */
  def writeMsg[T: Writer](t: T): upack.Msg = transform(t).to[upack.Msg]

  /**
    * Write the given Scala value as a JSON string to the given Writer
    */
  def writeTo[T: Writer](t: T,
                         out: java.io.Writer,
                         indent: Int = -1,
                         escapeUnicode: Boolean = false): Unit = {
    transform(t).to(new ujson.Renderer(out, indent = indent, escapeUnicode))
  }
  def writeToOutputStream[T: Writer](t: T,
                                     out: java.io.OutputStream,
                                     indent: Int = -1,
                                     escapeUnicode: Boolean = false): Unit = {
    transform(t).to(new ujson.BaseByteRenderer(out, indent = indent, escapeUnicode))
  }
  def writeToByteArray[T: Writer](t: T,
                                  indent: Int = -1,
                                  escapeUnicode: Boolean = false) = {
    val out = new java.io.ByteArrayOutputStream()
    writeToOutputStream(t, out, indent, escapeUnicode)
    out.toByteArray
  }
  /**
    * Write the given Scala value as a JSON string via a `geny.Writable`
    */
  def stream[T: Writer](t: T,
                        indent: Int = -1,
                        escapeUnicode: Boolean = false): geny.Writable = new geny.Writable{
    override def httpContentType = Some("application/json")
    def writeBytesTo(out: java.io.OutputStream) = {
      transform(t).to(new ujson.BaseByteRenderer(out, indent = indent, escapeUnicode))
    }
  }
  /**
    * Write the given Scala value as a MessagePack binary to the given OutputStream
    */
  def writeBinaryTo[T: Writer](t: T, out: java.io.OutputStream): Unit = {
    streamBinary[T](t).writeBytesTo(out)
  }
  def writeBinaryToByteArray[T: Writer](t: T) = {
    val out = new java.io.ByteArrayOutputStream()
    streamBinary[T](t).writeBytesTo(out)
    out.toByteArray
  }
  /**
    * Write the given Scala value as a MessagePack binary via a `geny.Writable`
    */
  def streamBinary[T: Writer](t: T): geny.Writable = new geny.Writable{
    override def httpContentType = Some("application/octet-stream")
    def writeBytesTo(out: java.io.OutputStream) = transform(t).to(new upack.MsgPackWriter(out))
  }

  def writer[T: Writer] = implicitly[Writer[T]]

  def readwriter[T: ReadWriter] = implicitly[ReadWriter[T]]

  case class transform[T: Writer](t: T) extends upack.Readable with ujson.Readable {
    def transform[V](f: Visitor[_, V]): V = writer[T].transform(t, f)
    def to[V](f: Visitor[_, V]): V = transform(f)
    def to[V](implicit f: Reader[V]): V = transform(f)
  }


  /**
   * Mark a `ReadWriter[T]` as something that can be used as a key in a JSON
   * dictionary, such that `Map[T, V]` serializes to `{"a": "b", "c": "d"}`
   * rather than `[["a", "b"], ["c", "d"]]`
   */
  def stringKeyRW[T](readwriter: ReadWriter[T]): ReadWriter[T] = {
    new ReadWriter.Delegate[T](readwriter) {
      override def isJsonDictKey = true
      def write0[R](out: Visitor[_, R], v: T): R = readwriter.write0(out, v)
    }
  }

  /**
   * Mark a `Writer[T]` as something that can be used as a key in a JSON
   * dictionary, such that `Map[T, V]` serializes to `{"a": "b", "c": "d"}`
   * rather than `[["a", "b"], ["c", "d"]]`
   */
  def stringKeyW[T](readwriter: Writer[T]): Writer[T] = new Writer[T]{
    override def isJsonDictKey = true
    def write0[R](out: Visitor[_, R], v: T): R = readwriter.write0(out, v)
  }
  // End Api
}

/**
 * The default way of accessing upickle
 */
object default extends AttributeTagged{

}
/**
 * An instance of the upickle API that follows the old serialization for
 * tagged instances of sealed traits: as a list with two items, the first
 * being the type-tag and the second being the serialized object
 */
object legacy extends LegacyApi
trait LegacyApi extends Api with Annotator{
  def annotate[V](rw: Reader[V], n: String) = new TaggedReader.Leaf[V](n, rw)

  def annotate[V](rw: ObjectWriter[V], n: String, checker: Annotator.Checker): TaggedWriter[V] = {
    new TaggedWriter.Leaf[V](checker, n, rw)
  }

  def taggedExpectedMsg = "expected sequence"
  sealed trait TaggedReaderState
  object TaggedReaderState{
    case object Initializing extends TaggedReaderState
    case class Parsing(f: Reader[_]) extends TaggedReaderState
    case class Parsed(res: Any) extends TaggedReaderState
  }
  override def taggedArrayContext[T](taggedReader: TaggedReader[T], index: Int) = new ArrVisitor[Any, T] {
    var state: TaggedReaderState = TaggedReaderState.Initializing

    def subVisitor = state match{
      case TaggedReaderState.Initializing => StringReader
      case TaggedReaderState.Parsing(f) => f
      case TaggedReaderState.Parsed(res) => NoOpVisitor
    }

    def visitValue(v: Any, index: Int): Unit = state match{
      case TaggedReaderState.Initializing =>
        val typeName = objectTypeKeyReadMap(v.toString).toString
        val delegate = taggedReader.findReader(typeName)
        if (delegate == null) {
          throw new Abort("invalid tag for tagged object: " + typeName)
        }
        state = TaggedReaderState.Parsing(delegate)
      case TaggedReaderState.Parsing(f) =>
        state = TaggedReaderState.Parsed(v)
      case TaggedReaderState.Parsed(res) => res.asInstanceOf[T]
        throw new Abort("expected tagged dictionary")
    }

    def visitEnd(index: Int) = state match{
      case TaggedReaderState.Parsed(res) => res.asInstanceOf[T]
      case _ => throw new Abort("expected tagged dictionary")
    }

  }
  def taggedWrite[T, R](w: ObjectWriter[T], tag: String, out: Visitor[_,  R], v: T): R = {
    val ctx = out.asInstanceOf[Visitor[Any, R]].visitArray(2, -1)
    ctx.visitValue(ctx.subVisitor.visitString(objectTypeKeyWriteMap(tag), -1), -1)

    ctx.visitValue(w.write(ctx.subVisitor, v), -1)

    ctx.visitEnd(-1)
  }
}

/**
 * A `upickle.Api` that follows the default sealed-trait-instance-tagging
 * behavior of using an attribute, but allow you to control what the name
 * of the attribute is.
 */
trait AttributeTagged extends Api with Annotator{
  def tagName = "$type"
  def annotate[V](rw: Reader[V], n: String) = {
    new TaggedReader.Leaf[V](n, rw)
  }

  def annotate[V](rw: ObjectWriter[V], n: String, checker: Annotator.Checker): TaggedWriter[V] = {
    new TaggedWriter.Leaf[V](checker, n, rw)
  }

  def taggedExpectedMsg = "expected dictionary"
  override def taggedObjectContext[T](taggedReader: TaggedReader[T], index: Int) = {
    new ObjVisitor[Any, T]{
      private[this] var fastPath = false
      private[this] var context: ObjVisitor[Any, _] = null
      def subVisitor: Visitor[_, _] =
        if (context == null) upickle.core.StringVisitor
        else context.subVisitor

      def visitKey(index: Int) = {
        if (context != null) context.visitKey(index)
        else upickle.core.StringVisitor
      }
      def visitKeyValue(s: Any): Unit = {
        if (context != null) context.visitKeyValue(s)
        else {
          if (s.toString == tagName) () //do nothing
          else {
            // otherwise, go slow path
            val slowCtx = IndexedValue.Builder.visitObject(-1, true, index).narrow
            val keyVisitor = slowCtx.visitKey(index)
            val xxx = keyVisitor.visitString(s.toString, index)
            slowCtx.visitKeyValue(xxx)
            context = slowCtx
          }
        }
      }

      def visitValue(v: Any, index: Int): Unit = {
        if (context != null) context.visitValue(v, index)
        else {
          val typeName = objectTypeKeyReadMap(v.toString).toString
          val facade0 = taggedReader.findReader(typeName)
          if (facade0 == null) {
            throw new Abort("invalid tag for tagged object: " + typeName)
          }
          val fastCtx = facade0.visitObject(-1, true, index)
          context = fastCtx
          fastPath = true
        }
      }
      def visitEnd(index: Int) = {
        def missingKeyMsg = s"""Missing key "$tagName" for tagged dictionary"""
        if (context == null) throw new Abort(missingKeyMsg)
        else if (fastPath) context.visitEnd(index).asInstanceOf[T]
        else{
          val x = context.visitEnd(index).asInstanceOf[IndexedValue.Obj]
          val keyAttr = x.value0.find(_._1.toString == tagName)
            .getOrElse(throw new Abort(missingKeyMsg))
            ._2
          val key = keyAttr.asInstanceOf[IndexedValue.Str].value0.toString
          val delegate = taggedReader.findReader(key)
          if (delegate == null){
            throw new AbortException("invalid tag for tagged object: " + key, keyAttr.index, -1, -1, null)
          }
          val ctx2 = delegate.visitObject(-1, true, -1)
          for (p <- x.value0) {
            val (k0, v) = p
            val k = k0.toString
            if (k != tagName){
              val keyVisitor = ctx2.visitKey(-1)

              ctx2.visitKeyValue(keyVisitor.visitString(k, -1))
              ctx2.visitValue(IndexedValue.transform(v, ctx2.subVisitor), -1)
            }
          }
          ctx2.visitEnd(index)
        }
      }

    }
  }
  def taggedWrite[T, R](w: ObjectWriter[T], tag: String, out: Visitor[_,  R], v: T): R = {

    if (w.isInstanceOf[SingletonWriter[_]]) out.visitString(tag, -1)
    else {
      val ctx = out.asInstanceOf[Visitor[Any, R]].visitObject(w.length(v) + 1, true, -1)
      val keyVisitor = ctx.visitKey(-1)

      ctx.visitKeyValue(keyVisitor.visitString(tagName, -1))
      ctx.visitValue(ctx.subVisitor.visitString(objectTypeKeyWriteMap(tag), -1), -1)
      w.writeToObject(ctx, v)
      val res = ctx.visitEnd(-1)
      res
    }
  }
}
