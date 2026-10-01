import SwiftUI
import CoatiCheckShared

struct ContentView: View {
    @State private var greeting = "Cargando..."

    var body: some View {
        VStack {
            Text(greeting)
                .padding()
        }
        .onAppear {
            greeting = Greeting().greet()
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
