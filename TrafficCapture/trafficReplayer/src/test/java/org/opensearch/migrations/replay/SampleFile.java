package org.opensearch.migrations.replay;

public class SampleFile {
    public static String contents = "[2023-02-15T08:54:25,257][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] REGISTERED\n" +
            "[2023-02-15T08:54:25,258][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] ACTIVE\n" +
            "[2023-02-15T08:54:25,271][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] READ 78:" +
            "R0VUIC8gSFRUUC8xLjENCkhvc3Q6IGxvY2FsaG9zdDo5MjAwDQpVc2VyLUFnZW50OiBjdXJsLzcuNzkuMQ0KQWNjZXB0OiAqLyoNCg0K\n" +
            "[2023-02-15T08:54:25,271][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] READ 78:" +
            "R0VUIC8gSFRUUC8xLjENCkhvc3Q6IGxvY2FsaG9zdDo5MjAwDQpVc2VyLUFnZW50OiBjdXJsLzcuNzkuMQ0KQWNjZXB0OiAqLyoNCg0K\n" +
            "[2023-02-15T08:54:25,313][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] WRITE 87:" +
            "SFRUUC8xLjEgMjAwIE9LDQpjb250ZW50LXR5cGU6IGFwcGxpY2F0aW9uL2pzb247IGNoYXJzZXQ9VVRGLTgNCmNvbnRlbnQtbGVuZ3RoOiA0OTINCg0K\n" +
            "[2023-02-15T08:54:25,314][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] WRITE 492:" +
            "ewogICJuYW1lIiA6ICIiLAogICJjbHVzdGVyX25hbWUiIDogIm9wZW5zZWFyY2giLAogICJjbHVzdGVyX3V1aWQiIDogImpXbWpodFpvVEVhc09uTWstTTE2NVEiLAogICJ2ZXJzaW9uIiA6IHsKICAgICJkaXN0cmlidXRpb24iIDogIm9wZW5zZWFyY2giLAogICAgIm51bWJlciIgOiAiMy4wLjAiLAogICAgImJ1aWxkX3R5cGUiIDogInRhciIsCiAgICAiYnVpbGRfaGFzaCIgOiAidW5rbm93biIsCiAgICAiYnVpbGRfZGF0ZSIgOiAidW5rbm93biIsCiAgICAiYnVpbGRfc25hcHNob3QiIDogdHJ1ZSwKICAgICJsdWNlbmVfdmVyc2lvbiIgOiAiOS41LjAiLAogICAgIm1pbmltdW1fd2lyZV9jb21wYXRpYmlsaXR5X3ZlcnNpb24iIDogIjIuNi4wIiwKICAgICJtaW5pbXVtX2luZGV4X2NvbXBhdGliaWxpdHlfdmVyc2lvbiIgOiAiMi4wLjAiCiAgfSwKICAidGFnbGluZSIgOiAiVGhlIE9wZW5TZWFyY2ggUHJvamVjdDogaHR0cHM6Ly9vcGVuc2VhcmNoLm9yZy8iCn0K\n" +
            "[2023-02-15T08:54:25,314][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x9c4b460b, L:/127.0.0.1:9200 - R:/127.0.0.1:58666] WRITABILITY CHANGED\n" +
            "[2023-02-15T08:54:25,314][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] FLUSH\n" +
            "[2023-02-15T08:54:25,316][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] READ COMPLETE\n" +
            "[2023-02-15T08:54:25,317][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 - R:/127.0.0.1:58665] READ COMPLETE\n" +
            "[2023-02-15T08:54:25,318][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 ! R:/127.0.0.1:58665] INACTIVE\n" +
            "[2023-02-15T08:54:25,319][TRACE][o.o.h.t.WireLogger       ] [] [id: 0x1d2fd12f, L:/127.0.0.1:9200 ! R:/127.0.0.1:58665] UNREGISTERED\n";
}
