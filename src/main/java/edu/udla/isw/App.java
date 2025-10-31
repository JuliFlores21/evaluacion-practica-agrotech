package edu.udla.isw;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.main.Main;
import org.apache.commons.dbcp2.BasicDataSource;

public class App extends RouteBuilder {

  // Usa SIEMPRE la misma base (con Documents)
  private static final String BASE = "C:/Users/julia/Documents/AgroTech/evaluacion-practica-agrotech";
  private static final String INPUT_DIR  = "file:" + BASE + "/lab01/input";
  private static final String OUTPUT_DIR = "file:" + BASE + "/lab01/output";

  public static void main(String[] args) throws Exception {
    Main main = new Main();
    BasicDataSource ds = new BasicDataSource();
    ds.setUrl("jdbc:sqlite:database/agrotech.db");
    ds.setDriverClassName("org.sqlite.JDBC");
    main.bind("dataSource", ds);

    main.configure().addRoutesBuilder(new RouteBuilder() {
      @Override public void configure() {
        from("timer:initdb?repeatCount=1")
          .setBody(constant(
            "CREATE TABLE IF NOT EXISTS lecturas (" +
            "id_sensor VARCHAR(10), fecha TEXT, humedad DOUBLE, temperatura DOUBLE)"
          ))
          .to("jdbc:dataSource");
      }
    });

    main.configure().addRoutesBuilder(new App());
    main.run(args);
  }

  @Override public void configure() {

   
    // 1) File Transfer: CSV -> JSON (rutas CONSISTENTES)
    from(INPUT_DIR + "?noop=true&initialDelay=0&delay=1000&include=.*\\.csv")
      .routeId("file-transfer")
      .log("[SENSDATA] Archivo detectado: ${file:name}")
      .unmarshal().csv()
      .process(exchange -> {
        var rows = (java.util.List<java.util.List<String>>) exchange.getIn().getBody();
        var out  = new java.util.ArrayList<java.util.Map<String,Object>>();
        for (int i = 1; i < rows.size(); i++) {
          var r = rows.get(i);
          var m = new java.util.HashMap<String,Object>();
          m.put("id_sensor",   r.get(0));
          m.put("fecha",       r.get(1));
          m.put("humedad",     Double.valueOf(r.get(2)));
          m.put("temperatura", Double.valueOf(r.get(3)));
          out.add(m);
        }
        exchange.getIn().setBody(out);
      })
      .marshal().json()
      .log("[AGROANALYZER] JSON generado: ${body}")
      .to(OUTPUT_DIR); // <-- ahora va a Documents también

    // 2) Insertar en BD desde el MISMO output
    from(OUTPUT_DIR + "?include=.*\\.json&noop=true")
      .routeId("db-insert")
      .unmarshal().json()
      .split(body())
        .setBody(simple(
          "INSERT INTO lecturas (id_sensor, fecha, humedad, temperatura) " +
          "VALUES ('${body[id_sensor]}','${body[fecha]}',${body[humedad]},${body[temperatura]})"
        ))
        .to("jdbc:dataSource")
        .log("[AGROANALYZER] Insertado en BD: ${body}")
      .end();

    // 3) RPC simulado
    from("direct:rpc.obtenerUltimo")
      .routeId("rpc-servidor")
      .log("[SERVIDOR] Solicitud recibida para sensor ${header.id_sensor}")
      .bean(ServicioAnalitica.class, "getUltimoValor");

    from("timer:rpc?repeatCount=1")
      .setBody(constant("S001"))
      .to("direct:solicitarLectura");

    from("direct:solicitarLectura")
      .routeId("rpc-cliente")
      .setHeader("id_sensor", simple("${body}"))
      .log("[CLIENTE] Solicitando lectura del sensor ${header.id_sensor}")
      .to("direct:rpc.obtenerUltimo")
      .log("[CLIENTE] Respuesta recibida: ${body}");
  }
}
