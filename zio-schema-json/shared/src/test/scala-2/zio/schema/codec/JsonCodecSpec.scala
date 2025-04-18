package zio.schema.codec

import java.time.{ ZoneId, ZoneOffset }

import scala.collection.immutable.ListMap

import zio.Console._
import zio._
import zio.json.JsonDecoder.JsonError
import zio.json.{ DeriveJsonEncoder, JsonEncoder }
import zio.schema.CaseSet._
import zio.schema._
import zio.schema.annotation._
import zio.schema.codec.DecodeError.ReadError
import zio.schema.codec.JsonCodec.JsonEncoder.charSequenceToByteChunk
import zio.schema.codec.JsonCodecSpec.PaymentMethod.{ CreditCard, PayPal, WireTransfer }
import zio.schema.codec.JsonCodecSpec.Subscription.{ OneTime, Recurring }
import zio.schema.meta.MetaSchema
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object JsonCodecSpec extends ZIOSpecDefault {

  def spec: Spec[TestEnvironment, Any] =
    suite("JsonCodec Spec")(
      encoderSuite,
      decoderSuite,
      encoderDecoderSuite
    ) @@ timeout(90.seconds)

  // TODO: Add tests for the pipeline contract.

  private val encoderSuite = suite("encoding")(
    suite("primitive")(
      test("unit") {
        assertEncodesJson(Schema[Unit], (), "{}")
      },
      test("string")(
        check(Gen.string)(s => assertEncodes(Schema[String], s, stringify(s)))
      ),
      test("ZoneOffset") {
        assertEncodesJson(Schema.Primitive(StandardType.ZoneOffsetType), ZoneOffset.UTC)
      },
      test("ZoneId") {
        assertEncodesJson(Schema.Primitive(StandardType.ZoneIdType), ZoneId.systemDefault())
      }
    ),
    suite("optional")(
      test("of primitives") {
        assertEncodesJson(
          Schema.Optional(Schema.Primitive(StandardType.StringType)),
          Some("value")
        ) &>
          assertEncodesJson(
            Schema.Optional(Schema.Primitive(StandardType.StringType)),
            None
          )
      }
    ),
    suite("tuple")(
      test("of primitives") {
        assertEncodesJson(
          Schema.Tuple2(
            Schema.Primitive(StandardType.StringType),
            Schema.Primitive(StandardType.IntType)
          ),
          ("L", 1)
        )
      }
    ),
    suite("sequence")(
      test("of primitives") {
        assertEncodesJson(
          Schema.chunk(Schema.Primitive(StandardType.StringType)),
          Chunk("a", "b", "c")
        )
      }
    ),
    suite("Map")(
      test("of complex keys and values") {
        assertEncodes(
          Schema.map[Key, Value],
          Map(Key("a", 0) -> Value(0, true), Key("b", 1) -> Value(1, false)),
          charSequenceToByteChunk(
            """[[{"name":"a","index":0},{"first":0,"second":true}],[{"name":"b","index":1},{"first":1,"second":false}]]"""
          )
        )
      }
    ),
    suite("Set")(
      test("of complex values") {
        assertEncodes(
          Schema.set[Value],
          Set(Value(0, true), Value(1, false)),
          charSequenceToByteChunk(
            """[{"first":0,"second":true},{"first":1,"second":false}]"""
          )
        )
      }
    ),
    suite("record")(
      test("of primitives") {
        assertEncodes(
          recordSchema,
          ListMap[String, Any]("foo" -> "s", "bar" -> 1),
          charSequenceToByteChunk("""{"foo":"s","bar":1}""")
        )
      },
      test("of records") {
        assertEncodes(
          nestedRecordSchema,
          ListMap[String, Any]("l1" -> "s", "l2" -> ListMap("foo" -> "s", "bar" -> 1)),
          charSequenceToByteChunk("""{"l1":"s","l2":{"foo":"s","bar":1}}""")
        )
      },
      test("case class") {
        check(searchRequestGen) { searchRequest =>
          assertEncodesJson(
            searchRequestSchema,
            searchRequest
          )
        }
      },
      test("case object") {
        assertEncodesJson(
          schemaObject,
          Singleton,
          "{}"
        )
      },
      test("record with option fields encoded as null") {
        assertEncodes(
          recordWithOptionSchema,
          ListMap[String, Any]("foo" -> Some("s"), "bar" -> None),
          charSequenceToByteChunk("""{"foo":"s","bar":null}""")
        )
      },
      test("case class with option fields omitted when empty") {
        assertEncodes(
          WithOptionFields.schema,
          WithOptionFields(Some("s"), None),
          charSequenceToByteChunk("""{"a":"s"}""")
        )
      }
    ),
    suite("enumeration")(
      test("of primitives") {
        assertEncodes(
          enumSchema,
          ("foo"),
          charSequenceToByteChunk("""{"string":"foo"}""")
        )
      },
      test("ADT") {
        assertEncodes(
          Schema[Enumeration],
          Enumeration(StringValue("foo")),
          charSequenceToByteChunk("""{"oneOf":{"StringValue":{"value":"foo"}}}""")
        )
      },
      test("ADT with annotation") {
        assertEncodes(
          Schema[Enumeration2],
          Enumeration2(StringValue2("foo2")),
          charSequenceToByteChunk("""{"oneOf":{"_type":"StringValue2","value":"foo2"}}""")
        )
      },
      test("case class ") {
        assertEncodes(
          searchRequestWithTransientFieldSchema,
          SearchRequestWithTransientField("foo", 10, 20, "bar"),
          charSequenceToByteChunk("""{"query":"foo","pageNumber":10,"resultPerPage":20}""")
        )
      },
      test("case name annotation") {
        assertEncodes(
          PaymentMethod.schema,
          WireTransfer("foo", "bar"),
          charSequenceToByteChunk("""{"wire_transfer":{"accountNumber":"foo","bankCode":"bar"}}""")
        )
      },
      test("transient case annotation") {
        assertEncodes(
          PaymentMethod.schema,
          PayPal("foo@bar.com"),
          charSequenceToByteChunk("""{}""")
        )
      },
      test("case name annotation with discriminator") {
        assertEncodes(
          Subscription.schema,
          Recurring("monthly", 10),
          charSequenceToByteChunk("""{"type":"recurring","period":"monthly","amount":10}""")
        )
      },
      test("case name annotation with empty fields") {
        assertEncodes(
          Subscription.schema,
          Subscription.Unlimited(None),
          charSequenceToByteChunk("""{"type":"unlimited"}""")
        )
      },
      suite("with no discriminator")(
        test("example 1") {
          assertEncodes(
            Prompt.schema,
            Prompt.Single("hello"),
            charSequenceToByteChunk("""{"value":"hello"}""")
          )
        },
        test("example 2") {
          assertEncodes(
            Prompt.schema,
            Prompt.Multiple(List("hello", "world")),
            charSequenceToByteChunk("""{"value":["hello","world"]}""")
          )
        }
      )
    ),
    suite("dynamic direct mapping")(
      test("record") {
        assertEncodes(
          Schema.dynamicValue.annotate(directDynamicMapping()),
          DynamicValue.Record(
            TypeId.Structural,
            ListMap(
              "foo" -> DynamicValue.Primitive("s", StandardType.StringType),
              "bar" -> DynamicValue.Primitive(1, StandardType.IntType)
            )
          ),
          charSequenceToByteChunk("""{"foo":"s","bar":1}""")
        )
      }
    )
  )

  private val decoderSuite = suite("decoding")(
    suite("primitive")(
      test("unit") {
        assertEncodesJson(Schema[Unit], (), "{}")
      },
      suite("string")(
        test("any") {
          check(Gen.string)(s => assertDecodes(Schema[String], s, stringify(s)))
        }
      )
    ),
    suite("generic record")(
      test("with extra fields") {
        assertDecodes(
          recordSchema,
          ListMap[String, Any]("foo" -> "s", "bar" -> 1),
          charSequenceToByteChunk("""{"foo":"s","bar":1,"baz":2}""")
        )
      }
    ),
    suite("transform")(
      test("string") {
        val stringSchema    = Schema.Primitive(StandardType.StringType)
        val transformSchema = stringSchema.transform[Int](_ => 1, _.toString)
        assertDecodes(transformSchema, 1, stringify("string"))
      },
      test("failed") {
        val errorMessage = "I'm sorry Dave, I can't do that"
        val schema: Schema[Int] = Schema
          .Primitive(StandardType.StringType)
          .transformOrFail[Int](_ => Left(errorMessage), i => Right(i.toString))
        check(Gen.int(Int.MinValue, Int.MaxValue)) { int =>
          assertDecodesToError(
            schema,
            JsonEncoder.string.encodeJson(int.toString, None),
            JsonError.Message(errorMessage) :: Nil
          )
        }
      }
    ),
    suite("case class")(
      test("case object") {
        assertDecodes(schemaObject, Singleton, charSequenceToByteChunk("{}"))
      },
      test("optional") {
        assertDecodes(
          optionalSearchRequestSchema,
          OptionalSearchRequest("test", 0, 10, Schema[String].defaultValue.getOrElse("")),
          charSequenceToByteChunk("""{"query":"test","pageNumber":0,"resultPerPage":10}""")
        )
      },
      test("reject extra fields") {
        assertDecodesToError(
          PersonWithRejectExtraFields.schema,
          """{"name":"test","age":10,"extraField":10}""",
          JsonError.Message("extra field") :: Nil
        )
      },
      test("transient field annotation") {
        assertDecodes(
          searchRequestWithTransientFieldSchema,
          SearchRequestWithTransientField("test", 0, 10, Schema[String].defaultValue.getOrElse("")),
          charSequenceToByteChunk("""{"query":"test","pageNumber":0,"resultPerPage":10}""")
        )
      },
      test("fieldDefaultValue") {
        assertDecodes(
          fieldDefaultValueSearchRequestSchema,
          FieldDefaultValueSearchRequest("test", 0, 10, "test"),
          charSequenceToByteChunk("""{"query":"test","pageNumber":0,"resultPerPage":10}""")
        )
      },
      test("field name with alias - id") {
        assertDecodes(
          Order.schema,
          Order(1, BigDecimal.valueOf(10), "test"),
          charSequenceToByteChunk("""{"id":1,"value":10,"description":"test"}""")
        )
      },
      test("field name with alias - order_id") {
        assertDecodes(
          Order.schema,
          Order(1, BigDecimal.valueOf(10), "test"),
          charSequenceToByteChunk("""{"id":1,"value":10,"description":"test"}""")
        )
      },
      test("field name with alias - no alias") {
        assertDecodes(
          Order.schema,
          Order(1, BigDecimal.valueOf(10), "test"),
          charSequenceToByteChunk("""{"orderId":1,"value":10,"description":"test"}""")
        )
      },
      test("with option fields encoded as null") {
        assertDecodes(
          recordWithOptionSchema,
          ListMap[String, Any]("foo" -> Some("s"), "bar" -> None),
          charSequenceToByteChunk("""{"foo":"s","bar":null}""")
        )
      },
      test("case class with option fields encoded as null") {
        assertDecodes(
          WithOptionFields.schema,
          WithOptionFields(Some("s"), None),
          charSequenceToByteChunk("""{"a":"s","b":null}""")
        )
      },
      test("case class with option fields omitted when empty") {
        assertDecodes(
          WithOptionFields.schema,
          WithOptionFields(Some("s"), None),
          charSequenceToByteChunk("""{"a":"s"}""")
        )
      }
    ),
    suite("enums")(
      test("case name aliases - default") {
        assertDecodes(
          PaymentMethod.schema,
          CreditCard("foo", 12, 2022),
          charSequenceToByteChunk("""{"CreditCard":{"number":"foo","expirationMonth":12,"expirationYear":2022}}""")
        )
      },
      test("case name aliases - first alias") {
        assertDecodes(
          PaymentMethod.schema,
          CreditCard("foo", 12, 2022),
          charSequenceToByteChunk("""{"credit_card":{"number":"foo","expirationMonth":12,"expirationYear":2022}}""")
        )
      },
      test("case name aliases - second alias") {
        assertDecodes(
          PaymentMethod.schema,
          CreditCard("foo", 12, 2022),
          charSequenceToByteChunk("""{"cc":{"number":"foo","expirationMonth":12,"expirationYear":2022}}""")
        )
      },
      test("case name") {
        assertDecodes(
          PaymentMethod.schema,
          WireTransfer("foo", "bar"),
          charSequenceToByteChunk("""{"wire_transfer":{"accountNumber":"foo","bankCode":"bar"}}""")
        )
      }
    ),
    suite("enums - with discriminator")(
      test("case name") {
        assertDecodes(
          Subscription.schema,
          Recurring("monthly", 100),
          charSequenceToByteChunk("""{"type":"recurring","period":"monthly","amount":100}""")
        )
      },
      test("case name aliases - first alias") {
        assertDecodes(
          Subscription.schema,
          OneTime(1000),
          charSequenceToByteChunk("""{"type":"one_time","amount":1000}""")
        )
      },
      test("case name aliases - second alias") {
        assertDecodes(
          Subscription.schema,
          OneTime(1000),
          charSequenceToByteChunk("""{"type":"onetime","amount":1000}""")
        )
      },
      test("case name - empty fields") {
        assertDecodes(
          Subscription.schema,
          Subscription.Unlimited(None),
          charSequenceToByteChunk("""{"type":"unlimited"}""")
        )
      }
    ),
    suite("enums - with no discriminator")(
      test("example 1") {
        assertDecodes(
          Prompt.schema,
          Prompt.Single("hello"),
          charSequenceToByteChunk("""{"value":"hello"}""")
        )
      },
      test("example 2") {
        assertDecodes(
          Prompt.schema,
          Prompt.Multiple(List("hello", "world")),
          charSequenceToByteChunk("""{"value":["hello","world"]}""")
        )
      }
    ),
    suite("dynamic direct mapping")(
      test("record") {
        assertDecodes(
          Schema.dynamicValue.annotate(directDynamicMapping()),
          DynamicValue.Record(
            TypeId.Structural,
            ListMap(
              "foo" -> DynamicValue.Primitive("s", StandardType.StringType),
              "bar" -> DynamicValue.Primitive(java.math.BigDecimal.valueOf(1), StandardType.BigDecimalType)
            )
          ),
          charSequenceToByteChunk("""{"foo":"s","bar":1}""")
        )
      }
    )
  )

  private val encoderDecoderSuite = suite("encoding then decoding")(
    test("unit") {
      assertEncodesThenDecodes(Schema[Unit], ())
    },
    test("primitive") {
      check(SchemaGen.anyPrimitiveAndValue) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    suite("either")(
      test("of primitives") {
        check(SchemaGen.anyEitherAndValue) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of tuples") {
        check(
          for {
            left  <- SchemaGen.anyTupleAndValue
            right <- SchemaGen.anyTupleAndValue
          } yield (
            Schema.Either(left._1.asInstanceOf[Schema[(Any, Any)]], right._1.asInstanceOf[Schema[(Any, Any)]]),
            Right(right._2)
          )
        ) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of enums") {
        check(for {
          (left, value) <- SchemaGen.anyEnumerationAndValue
          (right, _)    <- SchemaGen.anyEnumerationAndValue
        } yield (Schema.Either(left, right), Left(value))) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of sequence") {
        check(
          for {
            left  <- SchemaGen.anySequenceAndValue
            right <- SchemaGen.anySequenceAndValue
          } yield (
            Schema.Either(left._1.asInstanceOf[Schema[Chunk[Any]]], right._1.asInstanceOf[Schema[Chunk[Any]]]),
            Left(left._2)
          )
        ) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of map") {
        check(
          for {
            left  <- SchemaGen.anyMapAndValue
            right <- SchemaGen.anyMapAndValue
          } yield (
            Schema
              .Either(left._1.asInstanceOf[Schema[Map[Any, Any]]], right._1.asInstanceOf[Schema[Map[Any, Any]]]),
            Left(left._2)
          )
        ) {
          case (schema, value) =>
            assertEncodesThenDecodes[scala.util.Either[Map[Any, Any], Map[Any, Any]]](
              schema.asInstanceOf[Schema[scala.util.Either[Map[Any, Any], Map[Any, Any]]]],
              value.asInstanceOf[scala.util.Either[Map[Any, Any], Map[Any, Any]]]
            )
        }
      },
      test("of set") {
        check(
          for {
            left  <- SchemaGen.anySetAndValue
            right <- SchemaGen.anySetAndValue
          } yield (
            Schema
              .Either(left._1.asInstanceOf[Schema[Set[Any]]], right._1.asInstanceOf[Schema[Set[Any]]]),
            Left(left._2)
          )
        ) {
          case (schema, value) =>
            assertEncodesThenDecodes[scala.util.Either[Set[Any], Set[Any]]](
              schema.asInstanceOf[Schema[scala.util.Either[Set[Any], Set[Any]]]],
              value.asInstanceOf[scala.util.Either[Set[Any], Set[Any]]]
            )
        }
      },
      test("Map of complex keys and values") {
        assertEncodes(
          Schema.map[Key, Value],
          Map(Key("a", 0) -> Value(0, true), Key("b", 1) -> Value(1, false)),
          charSequenceToByteChunk(
            """[[{"name":"a","index":0},{"first":0,"second":true}],[{"name":"b","index":1},{"first":1,"second":false}]]"""
          )
        )
      },
      test("Set of complex values") {
        assertEncodes(
          Schema.set[Value],
          Set(Value(0, true), Value(1, false)),
          charSequenceToByteChunk(
            """[{"first":0,"second":true},{"first":1,"second":false}]"""
          )
        )
      },
      test("of records") {
        check(for {
          (left, a)       <- SchemaGen.anyRecordAndValue()
          primitiveSchema <- SchemaGen.anyPrimitive
        } yield (Schema.Either(left, primitiveSchema), Left(a))) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of records of records") {
        check(for {
          (left, _)  <- SchemaGen.anyRecordOfRecordsAndValue
          (right, b) <- SchemaGen.anyRecordOfRecordsAndValue
        } yield (Schema.Either(left, right), Right(b))) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      },
      test("mixed") {
        check(for {
          (left, _)      <- SchemaGen.anyEnumerationAndValue
          (right, value) <- SchemaGen.anySequenceAndValue
        } yield (Schema.Either(left, right), Right(value))) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      }
    ),
    suite("optional")(
      test("of primitive") {
        check(SchemaGen.anyOptionalAndValue) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of tuple") {
        check(SchemaGen.anyTupleAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of record") {
        check(SchemaGen.anyRecordAndValue()) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of enumeration") {
        check(SchemaGen.anyEnumerationAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of sequence") {
        check(SchemaGen.anySequenceAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of Map") {
        check(SchemaGen.anyMapAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of Set") {
        check(SchemaGen.anySetAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of Map") {
        check(SchemaGen.anyMapAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of Set") {
        check(SchemaGen.anySetAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of Map") {
        check(SchemaGen.anyMapAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      },
      test("of Set") {
        check(SchemaGen.anySetAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.Optional(schema), Some(value)) &>
              assertEncodesThenDecodes(Schema.Optional(schema), None)
        }
      }
    ),
    test("tuple") {
      check(SchemaGen.anyTupleAndValue) {
        case (schema, value) => assertEncodesThenDecodes(schema, value)
      }
    },
    suite("sequence")(
      test("of primitives") {
        check(SchemaGen.anySequenceAndValue) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of records") {
        check(SchemaGen.anyCaseClassAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.chunk(schema), Chunk.fill(3)(value))
        }
      },
      test("of java.time.ZoneOffset") {
        //FIXME test independently because including ZoneOffset in StandardTypeGen.anyStandardType wreaks havoc.
        check(Gen.chunkOf(JavaTimeGen.anyZoneOffset)) { chunk =>
          assertEncodesThenDecodes(
            Schema.chunk(Schema.Primitive(StandardType.ZoneOffsetType)),
            chunk
          )
        }
      },
      test("of map") {
        check(SchemaGen.anyMapAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.chunk(schema), Chunk.fill(3)(value))
        }
      },
      test("of set") {
        check(SchemaGen.anySetAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(Schema.chunk(schema), Chunk.fill(3)(value))
        }
      }
    ),
    suite("map")(
      test("encodes and decodes a Map") {
        check(SchemaGen.anyMapAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      }
    ),
    suite("set")(
      test("encodes and decodes a Set") {
        check(SchemaGen.anySetAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      }
    ),
    suite("case class")(
      test("basic") {
        check(searchRequestGen) { value =>
          assertEncodesThenDecodes(searchRequestSchema, value)
        }
      },
      test("optional") {
        check(optionalSearchRequestGen) { value =>
          assertEncodesThenDecodes(optionalSearchRequestSchema, value)
        }
      },
      test("object") {
        assertEncodesThenDecodes(schemaObject, Singleton)
      }
    ),
    suite("record")(
      test("any") {
        check(SchemaGen.anyRecordAndValue()) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("minimal test case") {
        SchemaGen.anyRecordAndValue().runHead.flatMap {
          case Some((schema, value)) =>
            val key = new String(Array('\u0007', '\n'))
            val embedded = Schema.record(
              TypeId.Structural,
              Schema
                .Field[ListMap[String, _], ListMap[String, _]](
                  key,
                  schema,
                  get0 = (p: ListMap[String, _]) => p(key).asInstanceOf[ListMap[String, _]],
                  set0 = (p: ListMap[String, _], v: ListMap[String, _]) => p.updated(key, v)
                )
            )
            assertEncodesThenDecodes(embedded, ListMap(key -> value))
          case None => ZIO.fail("Should never happen!")
        }
      },
      test("record of records") {
        check(SchemaGen.anyRecordOfRecordsAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      },
      test("of primitives") {
        check(SchemaGen.anyRecordAndValue()) {
          case (schema, value) => assertEncodesThenDecodes(schema, value)
        }
      },
      test("of ZoneOffsets") {
        check(JavaTimeGen.anyZoneOffset) { zoneOffset =>
          assertEncodesThenDecodes(
            Schema.record(
              TypeId.parse("java.time.ZoneOffset"),
              Schema.Field(
                "zoneOffset",
                Schema.Primitive(StandardType.ZoneOffsetType),
                get0 = (p: ListMap[String, _]) => p("zoneOffset").asInstanceOf[ZoneOffset],
                set0 = (p: ListMap[String, _], v: ZoneOffset) => p.updated("zoneOffset", v)
              )
            ),
            ListMap[String, Any]("zoneOffset" -> zoneOffset)
          )
        }
      },
      test("of record") {
        assertEncodesThenDecodes(
          nestedRecordSchema,
          ListMap[String, Any]("l1" -> "s", "l2" -> ListMap[String, Any]("foo" -> "s", "bar" -> 1))
        )
      }
    ),
    suite("enumeration")(
      test("of primitives") {
        assertEncodesThenDecodes(
          enumSchema,
          "foo"
        )
      },
      test("ADT") {
        assertEncodesThenDecodes(
          Schema[Enumeration],
          Enumeration(StringValue("foo"))
        ) &> assertEncodesThenDecodes(Schema[Enumeration], Enumeration(IntValue(-1))) &> assertEncodesThenDecodes(
          Schema[Enumeration],
          Enumeration(BooleanValue(false))
        )
      },
      test("ADT with annotation") {
        assertEncodesThenDecodes(
          Schema[Enumeration2],
          Enumeration2(StringValue2("foo"))
        ) &> assertEncodesThenDecodes(
          Schema[Enumeration2],
          Enumeration2(StringValue2Multi("foo", "bar"))
        ) &> assertEncodesThenDecodes(Schema[Enumeration2], Enumeration2(IntValue2(-1))) &> assertEncodesThenDecodes(
          Schema[Enumeration2],
          Enumeration2(BooleanValue2(false))
        )
      }
    ),
    suite("transform")(
      test("any") {
        check(SchemaGen.anyTransformAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      }
    ),
    suite("any schema")(
      test("leaf") {
        check(SchemaGen.anyLeafAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      },
      test("recursive schema") {
        check(SchemaGen.anyTreeAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      },
      test("recursive data type") {
        check(SchemaGen.anyRecursiveTypeAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      },
      test("deep recursive data type") {
        check(SchemaGen.anyDeepRecursiveTypeAndValue) {
          case (schema, value) =>
            assertEncodesThenDecodes(schema, value)
        }
      } @@ TestAspect.size(200),
      suite("dynamic")(
        test("dynamic int") {
          check(
            DynamicValueGen.anyPrimitiveDynamicValue(StandardType.IntType)
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic instant") {
          check(
            DynamicValueGen.anyPrimitiveDynamicValue(StandardType.InstantType)
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic zoned date time") {
          check(
            DynamicValueGen.anyPrimitiveDynamicValue(
              StandardType.ZonedDateTimeType
            )
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic duration") {
          check(
            DynamicValueGen.anyPrimitiveDynamicValue(StandardType.DurationType)
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic string") {
          check(
            DynamicValueGen.anyPrimitiveDynamicValue(StandardType.StringType)
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic unit") {
          check(
            DynamicValueGen.anyPrimitiveDynamicValue(StandardType.UnitType)
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic json") {
          check(
            DynamicValueGen.anyDynamicValueOfSchema(SchemaGen.Json.schema)
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic tuple") {
          check(
            DynamicValueGen.anyDynamicTupleValue(Schema[String], Schema[Int])
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic record") {
          check(
            SchemaGen.anyRecord.flatMap(DynamicValueGen.anyDynamicValueOfSchema)
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic (string, record)") {
          check(
            SchemaGen.anyRecord.flatMap(record => DynamicValueGen.anyDynamicTupleValue(Schema[String], record))
          ) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic sequence") {
          check(SchemaGen.anyRecord.flatMap(DynamicValueGen.anyDynamicSequence)) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        },
        test("dynamic set") {
          check(SchemaGen.anyRecord.flatMap(DynamicValueGen.anyDynamicSet)) { dynamicValue =>
            assertEncodesThenDecodes(Schema.dynamicValue, dynamicValue)
          }
        }
      ),
      test("deserialized dynamic record converted to typed value") {
        check(SchemaGen.anyRecordAndValue(maxFieldCount = 15)) {
          case (schema, value) =>
            val dyn = DynamicValue.fromSchemaAndValue(schema, value)
            ZStream
              .succeed(dyn)
              .via(JsonCodec.schemaBasedBinaryCodec(Schema.dynamicValue).streamEncoder)
              .via(JsonCodec.schemaBasedBinaryCodec(Schema.dynamicValue).streamDecoder)
              .map(_.toTypedValue(schema))
              .runHead
              .map { result =>
                val resultList = result.get.toOption.get.toList
                assertTrue(
                  resultList == value.toList
                )
              }
        }
      } @@ TestAspect.size(1000) @@ TestAspect.samples(1000),
      suite("meta schema")(
        test("primitive string meta schema") {
          assertEncodesThenDecodes(MetaSchema.schema, Schema[String].ast)
        },
        test("case class meta schema") {
          assertEncodesThenDecodes(MetaSchema.schema, Schema[SchemaGen.Arity1].ast)
        },
        test("recursive class meta schema") {
          assertEncodesThenDecodes(MetaSchema.schema, Schema[SchemaGen.Json].ast)
        },
        test("dynamic value meta schema") {
          assertEncodesThenDecodes(MetaSchema.schema, Schema[DynamicValue].ast)
        },
        test("any meta schema") {
          check(SchemaGen.anySchema) { schema =>
            val metaSchema = schema.ast
            assertEncodesThenDecodes(MetaSchema.schema, metaSchema)
          }
        }
      )
    )
  )

  private def assertEncodes[A](schema: Schema[A], value: A, chunk: Chunk[Byte], print: Boolean = false) = {
    val stream = ZStream
      .succeed(value)
      .via(JsonCodec.schemaBasedBinaryCodec(schema).streamEncoder)
      .runCollect
      .tap { chunk =>
        printLine(s"${new String(chunk.toArray)}").when(print).ignore
      }
    assertZIO(stream)(equalTo(chunk))
  }

  private def assertEncodesJson[A](schema: Schema[A], value: A, json: String) = {
    val stream = ZStream
      .succeed(value)
      .via(JsonCodec.schemaBasedBinaryCodec[A](schema).streamEncoder)
      .runCollect
      .map(chunk => new String(chunk.toArray))
    assertZIO(stream)(equalTo(json))
  }

  private def assertEncodesJson[A](schema: Schema[A], value: A)(implicit enc: JsonEncoder[A]) = {
    val stream = ZStream
      .succeed(value)
      .via(JsonCodec.schemaBasedBinaryCodec[A](schema).streamEncoder)
      .runCollect
    assertZIO(stream)(equalTo(jsonEncoded(value)))
  }

  private def assertDecodesToError[A](schema: Schema[A], json: CharSequence, errors: List[JsonError]) = {
    val stream = ZStream
      .fromChunk(charSequenceToByteChunk(json))
      .via(JsonCodec.schemaBasedBinaryCodec[A](schema).streamDecoder)
      .catchAll(ZStream.succeed[DecodeError](_))
      .runHead
    assertZIO(stream)(isSome(equalTo(ReadError(Cause.empty, JsonError.render(errors)))))
  }

  private def assertDecodes[A](schema: Schema[A], value: A, chunk: Chunk[Byte]) = {
    val result = ZStream.fromChunk(chunk).via(JsonCodec.schemaBasedBinaryCodec[A](schema).streamDecoder).runCollect
    assertZIO(result)(equalTo(Chunk(value)))
  }

  private def assertEncodesThenDecodes[A](schema: Schema[A], value: A, print: Boolean = false) =
    assertEncodesThenDecodesWithDifferentSchemas(schema, schema, value, (x: A, y: A) => x == y, print)

  private def assertEncodesThenDecodesWithDifferentSchemas[A1, A2](
    encodingSchema: Schema[A1],
    decodingSchema: Schema[A2],
    value: A1,
    compare: (A1, A2) => Boolean,
    print: Boolean
  ) =
    ZStream
      .succeed(value)
      .tap(value => printLine(s"Input Value: $value").when(print).ignore)
      .via(JsonCodec.schemaBasedBinaryCodec[A1](encodingSchema).streamEncoder)
      .runCollect
      .tap(encoded => printLine(s"Encoded: ${new String(encoded.toArray)}").when(print).ignore)
      .flatMap { encoded =>
        ZStream
          .fromChunk(encoded)
          .via(JsonCodec.schemaBasedBinaryCodec[A2](decodingSchema).streamDecoder)
          .runCollect
          .tapError { err =>
            printLineError(s"Decoding failed for input ${new String(encoded.toArray)}\nError Message: $err")
          }
      }
      .tap(decoded => printLine(s"Decoded: $decoded").when(print).ignore)
      .either
      .map { result =>
        assertTrue(
          compare(value, result.toOption.get.head)
        )
      }

  private def flatten[A](value: A): A = value match {
    case Some(None)    => None.asInstanceOf[A]
    case Some(Some(a)) => flatten(Some(flatten(a))).asInstanceOf[A]
    case _             => value
  }

  implicit def mapEncoder[K, V](
    implicit keyEncoder: JsonEncoder[K],
    valueEncoder: JsonEncoder[V]
  ): JsonEncoder[Map[K, V]] =
    JsonEncoder.chunk(keyEncoder.zip(valueEncoder)).contramap[Map[K, V]](m => Chunk.fromIterable(m))

  private def jsonEncoded[A](value: A)(implicit enc: JsonEncoder[A]): Chunk[Byte] =
    charSequenceToByteChunk(enc.encodeJson(value, None))

  private def stringify(s: String): Chunk[Byte] = {
    val encoded = JsonEncoder.string.encodeJson(s, None)
    charSequenceToByteChunk(encoded)
  }

  case class SearchRequest(query: String, pageNumber: Int, resultPerPage: Int, nextPage: Option[String])

  object SearchRequest {
    implicit val encoder: JsonEncoder[SearchRequest] = DeriveJsonEncoder.gen[SearchRequest]
  }

  case class OptionalSearchRequest(
    query: String,
    pageNumber: Int,
    resultPerPage: Int,
    @optionalField nextPage: String
  )

  object OptionalSearchRequest {
    implicit val encoder: JsonEncoder[OptionalSearchRequest] = DeriveJsonEncoder.gen[OptionalSearchRequest]
  }

  @rejectExtraFields final case class PersonWithRejectExtraFields(name: String, age: Int)

  object PersonWithRejectExtraFields {
    implicit val encoder: JsonEncoder[PersonWithRejectExtraFields] =
      DeriveJsonEncoder.gen[PersonWithRejectExtraFields]

    val schema: Schema[PersonWithRejectExtraFields] = DeriveSchema.gen[PersonWithRejectExtraFields]
  }
  case class FieldDefaultValueSearchRequest(
    query: String,
    pageNumber: Int,
    resultPerPage: Int,
    @fieldDefaultValue("test") nextPage: String
  )

  object FieldDefaultValueSearchRequest {
    implicit val encoder: JsonEncoder[FieldDefaultValueSearchRequest] =
      DeriveJsonEncoder.gen[FieldDefaultValueSearchRequest]
  }

  private val searchRequestGen: Gen[Sized, SearchRequest] =
    for {
      query      <- Gen.string
      pageNumber <- Gen.int(Int.MinValue, Int.MaxValue)
      results    <- Gen.int(Int.MinValue, Int.MaxValue)
      nextPage   <- Gen.option(Gen.asciiString)
    } yield SearchRequest(query, pageNumber, results, nextPage)

  private val optionalSearchRequestGen: Gen[Sized, OptionalSearchRequest] =
    for {
      query      <- Gen.string
      pageNumber <- Gen.int(Int.MinValue, Int.MaxValue)
      results    <- Gen.int(Int.MinValue, Int.MaxValue)
      nextPage   <- Gen.asciiString
    } yield OptionalSearchRequest(query, pageNumber, results, nextPage)

  val searchRequestSchema: Schema[SearchRequest] = DeriveSchema.gen[SearchRequest]

  val optionalSearchRequestSchema: Schema[OptionalSearchRequest] = DeriveSchema.gen[OptionalSearchRequest]

  case class SearchRequestWithTransientField(
    query: String,
    pageNumber: Int,
    resultPerPage: Int,
    @transientField nextPage: String
  )

  val searchRequestWithTransientFieldSchema: Schema[SearchRequestWithTransientField] =
    DeriveSchema.gen[SearchRequestWithTransientField]

  val fieldDefaultValueSearchRequestSchema: Schema[FieldDefaultValueSearchRequest] =
    DeriveSchema.gen[FieldDefaultValueSearchRequest]

  val recordSchema: Schema[ListMap[String, _]] = Schema.record(
    TypeId.Structural,
    Schema.Field(
      "foo",
      Schema.Primitive(StandardType.StringType),
      get0 = (p: ListMap[String, _]) => p("foo").asInstanceOf[String],
      set0 = (p: ListMap[String, _], v: String) => p.updated("foo", v)
    ),
    Schema
      .Field(
        "bar",
        Schema.Primitive(StandardType.IntType),
        get0 = (p: ListMap[String, _]) => p("bar").asInstanceOf[Int],
        set0 = (p: ListMap[String, _], v: Int) => p.updated("bar", v)
      )
  )

  val recordWithOptionSchema: Schema[ListMap[String, _]] = Schema.record(
    TypeId.Structural,
    Schema.Field(
      "foo",
      Schema.Primitive(StandardType.StringType).optional,
      get0 = (p: ListMap[String, _]) => p("foo").asInstanceOf[Option[String]],
      set0 = (p: ListMap[String, _], v: Option[String]) => p.updated("foo", v)
    ),
    Schema
      .Field(
        "bar",
        Schema.Primitive(StandardType.IntType).optional,
        get0 = (p: ListMap[String, _]) => p("bar").asInstanceOf[Option[Int]],
        set0 = (p: ListMap[String, _], v: Option[Int]) => p.updated("bar", v)
      )
  )

  val nestedRecordSchema: Schema[ListMap[String, _]] = Schema.record(
    TypeId.Structural,
    Schema.Field(
      "l1",
      Schema.Primitive(StandardType.StringType),
      get0 = (p: ListMap[String, _]) => p("l1").asInstanceOf[String],
      set0 = (p: ListMap[String, _], v: String) => p.updated("l1", v)
    ),
    Schema.Field(
      "l2",
      recordSchema,
      get0 = (p: ListMap[String, _]) => p("l2").asInstanceOf[ListMap[String, _]],
      set0 = (p: ListMap[String, _], v: ListMap[String, _]) => p.updated("l2", v)
    )
  )

  val enumSchema: Schema[Any] = Schema.enumeration[Any, CaseSet.Aux[Any]](
    TypeId.Structural,
    caseOf[String, Any]("string")(_.asInstanceOf[String])(_.asInstanceOf[Any])(_.isInstanceOf[String]) ++ caseOf[
      Int,
      Any
    ]("int")(_.asInstanceOf[Int])(_.asInstanceOf[Any])(_.isInstanceOf[Int]) ++ caseOf[
      Boolean,
      Any
    ]("boolean")(_.asInstanceOf[Boolean])(_.asInstanceOf[Any])(_.isInstanceOf[Boolean])
  )

  sealed trait OneOf
  case class StringValue(value: String)   extends OneOf
  case class IntValue(value: Int)         extends OneOf
  case class BooleanValue(value: Boolean) extends OneOf

  object OneOf {
    implicit val schema: Schema[OneOf] = DeriveSchema.gen[OneOf]
  }

  case class Enumeration(oneOf: OneOf)

  object Enumeration {
    implicit val schema: Schema[Enumeration] = DeriveSchema.gen[Enumeration]
  }

  @discriminatorName("_type")
  sealed trait OneOf2
  case class StringValue2(value: String)                       extends OneOf2
  case class IntValue2(value: Int)                             extends OneOf2
  case class BooleanValue2(value: Boolean)                     extends OneOf2
  case class StringValue2Multi(value1: String, value2: String) extends OneOf2

  case class Enumeration2(oneOf: OneOf2)

  object Enumeration2 {
    implicit val schema: Schema[Enumeration2] = DeriveSchema.gen[Enumeration2]
  }

  case object Singleton
  implicit val schemaObject: Schema[Singleton.type] = DeriveSchema.gen[Singleton.type]

  case class Key(name: String, index: Int)

  object Key {
    implicit lazy val schema: Schema[Key] = DeriveSchema.gen[Key]
  }
  case class Value(first: Int, second: Boolean)

  object Value {
    implicit lazy val schema: Schema[Value] = DeriveSchema.gen[Value]
  }

  sealed trait PaymentMethod

  object PaymentMethod {

    @caseNameAliases("credit_card", "cc") final case class CreditCard(
      number: String,
      expirationMonth: Int,
      expirationYear: Int
    ) extends PaymentMethod

    @caseName("wire_transfer") final case class WireTransfer(accountNumber: String, bankCode: String)
        extends PaymentMethod

    @transientCase final case class PayPal(email: String) extends PaymentMethod

    implicit lazy val schema: Schema[PaymentMethod] = DeriveSchema.gen[PaymentMethod]
  }

  @discriminatorName("type") sealed trait Subscription

  object Subscription {

    @caseName("recurring") final case class Recurring(
      period: String,
      amount: Int
    ) extends Subscription

    @caseNameAliases("one_time", "onetime") final case class OneTime(
      amount: Int
    ) extends Subscription

    @caseName("unlimited") final case class Unlimited(until: Option[Long]) extends Subscription

    implicit lazy val schema: Schema[Subscription] = DeriveSchema.gen[Subscription]
  }

  case class Order(@fieldNameAliases("order_id", "id") orderId: Int, value: BigDecimal, description: String)

  object Order {
    implicit lazy val schema: Schema[Order] = DeriveSchema.gen[Order]
  }
  @noDiscriminator sealed trait Prompt

  object Prompt {
    final case class Single(value: String)         extends Prompt
    final case class Multiple(value: List[String]) extends Prompt

    implicit lazy val schema: Schema[Prompt] = DeriveSchema.gen[Prompt]
  }

  final case class WithOptionFields(a: Option[String], b: Option[Int])

  object WithOptionFields {
    implicit lazy val schema: Schema[WithOptionFields] = DeriveSchema.gen[WithOptionFields]
  }
}
