package br.com.careermatcher;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

public class Regioes {
    @Getter
    static private final Hashtable<Integer, List<String>> regioes = new Hashtable<>();

    static private final List<String> norte = new ArrayList<>(List.of("Acre", "Amapá", "Amazonas", "Pará", "Rondônia", "Roraima", "Tocantis"));
    static private final List<String> nordeste = new ArrayList<>(List.of("Alagoas", "Bahia", "Ceará", "Maranhão", "Paraíba", "Pernambuco", "Piauí", "Rio Grande do Norte", "Sergipe"));
    static private final List<String> centroOeste = new ArrayList<>(List.of("Goiás", "Mato Grosso", "Mato Grosso do Sul"));
    static private final List<String> sudeste = new ArrayList<>(List.of("Espírito Santo", "Minas Gerais", "Rio de janeiro", "São Paulo"));
    static private final List<String> sul = new ArrayList<>(List.of("Paraná", "Santa Catarina", "Rio Grande do Sul"));

    Regioes(){
        regioes.put(0, norte);
        regioes.put(1, nordeste);
        regioes.put(2, centroOeste);
        regioes.put(3, sudeste);
        regioes.put(4, sul);
    }

    public static Integer getIdRegiao(Hashtable<Integer, List<String>> regioes, String estado){
        for(int idRegiao : regioes.keySet()){
            if(regioes.get(idRegiao).contains(estado)){
                return idRegiao;
            }
        }
        return -1;
    }

}
