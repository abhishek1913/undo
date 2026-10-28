package dev.undo.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class JcsProperties {
  @Provide
  Arbitrary<JsonNode> json() {
    return node(3);
  }

  Arbitrary<JsonNode> node(int depth) {
    Arbitrary<JsonNode> leaf =
        Arbitraries.oneOf(
            Arbitraries.strings()
                .ofMaxLength(8)
                .map(s -> (JsonNode) Json.MAPPER.getNodeFactory().textNode(s)),
            Arbitraries.longs().map(l -> (JsonNode) Json.MAPPER.getNodeFactory().numberNode(l)),
            Arbitraries.of(true, false)
                .map(b -> (JsonNode) Json.MAPPER.getNodeFactory().booleanNode(b)),
            Arbitraries.just(Json.nul()));
    if (depth == 0) {
      return leaf;
    }
    Arbitrary<JsonNode> obj =
        Arbitraries.maps(Arbitraries.strings().ofMaxLength(6), node(depth - 1))
            .ofMaxSize(4)
            .map(
                m -> {
                  ObjectNode o = Json.obj();
                  m.forEach(o::set);
                  return o;
                });
    Arbitrary<JsonNode> arr =
        node(depth - 1)
            .list()
            .ofMaxSize(4)
            .map(
                l -> {
                  ArrayNode a = Json.arr();
                  l.forEach(a::add);
                  return a;
                });
    return Arbitraries.oneOf(leaf, obj, arr);
  }

  @Property
  void parsingTheCanonicalFormGivesTheSameValue(@ForAll("json") JsonNode x) {
    JsonNode back = Json.parse(Jcs.canonical(x));
    // Compare numbers by value: an int and a long holding the same number are the same JSON value.
    java.util.Comparator<JsonNode> byValue =
        (a, b) ->
            a.isNumber() && b.isNumber()
                ? a.bigIntegerValue().compareTo(b.bigIntegerValue())
                : a.equals(b) ? 0 : 1;
    assertThat(back.equals(byValue, x)).as(x + " vs " + back).isTrue();
  }

  @Property
  void canonicalFormIsStable(@ForAll("json") JsonNode x) {
    String once = Jcs.canonical(x);
    assertThat(Jcs.canonical(Json.parse(once))).isEqualTo(once);
  }
}
